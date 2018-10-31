package com.twitter.finagle.service

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.context.Deadline
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.logging.{HasLogLevel, Level}
import com.twitter.util.{Duration, Future, Stopwatch, Time, TokenBucket}

/**
 * DeadlineFilter provides an admission control module that can be pushed onto the stack to
 * reject requests with expired deadlines (deadlines are set in the TimeoutFilter).
 * For servers, DeadlineFilter.module should be pushed onto the stack before the stats filters so
 * stats are recorded for the request, and pushed after TimeoutFilter where a new Deadline is set.
 * For clients, DeadlineFilter.module should be pushed before the stats filters; higher in the stack
 * is preferable so requests are rejected as early as possible.
 *
 * @note Deadlines cross process boundaries and can span multiple nodes in a call graph.
 *       Even if a direct caller doesn't set a deadline, the server may still receive one and thus
 *       potentially fail the request. It is advised that all clients in the call graph be
 *       well prepared to handle NACKs before using this.
 */
object DeadlineFilter {

  private val DefaultRejectPeriod = 10.seconds

  // In the case of large delays, don't want to reject too many requests
  private val DefaultMaxRejectFraction = 0.2

  // Token Bucket is integer-based, so use a scale factor to facilitate
  // usage with the Double `maxRejectFraction`
  private[service] val RejectBucketScaleFactor = 1000.0

  /**
   * A class eligible for configuring a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.service.DeadlineFilter]] module.
   *
   * @param rejectPeriod No more than `maxRejectFraction` of requests will be
   *        discarded over the `rejectPeriod`. Must be `>= 1 second` and `<= 60 seconds`.
   */
  case class RejectPeriod(rejectPeriod: Duration) {
    require(
      rejectPeriod.inSeconds >= 1 && rejectPeriod.inSeconds <= 60,
      s"rejectPeriod must be [1 second, 60 seconds]: $rejectPeriod"
    )

    def mk(): (RejectPeriod, Stack.Param[RejectPeriod]) =
      (this, RejectPeriod.param)
  }

  object RejectPeriod {
    implicit val param: Stack.Param[DeadlineFilter.RejectPeriod] =
      Stack.Param(RejectPeriod(DefaultRejectPeriod))
  }

  /**
   * A class eligible for configuring a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.service.DeadlineFilter]] module.
   *
   * @param maxRejectFraction Maximum fraction of requests that can be
   *        rejected over `rejectPeriod`. Must be between 0.0 and 1.0.
   */
  case class MaxRejectFraction(maxRejectFraction: Double) {
    require(
      maxRejectFraction >= 0.0 && maxRejectFraction <= 1.0,
      s"maxRejectFraction must be between 0.0 and 1.0: $maxRejectFraction"
    )

    def mk(): (MaxRejectFraction, Stack.Param[MaxRejectFraction]) =
      (this, MaxRejectFraction.param)
  }
  object MaxRejectFraction {
    implicit val param: Stack.Param[DeadlineFilter.MaxRejectFraction] =
      Stack.Param(MaxRejectFraction(DefaultMaxRejectFraction))
  }

  /**
   * A class eligible for configuring a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.service.DeadlineFilter]] module.
   *
   * @param param `Disabled` will omit `DeadlineFilter` from the server
   *         stack. `DarkMode` will collect stats about deadlines but not reject requests.
   *        `Enabled` turns `DeadlineFilter` on.
   */
  case class Mode(param: Mode.FilterMode) {

    def mk(): (Mode, Stack.Param[Mode]) =
      (this, Mode.param)
  }

  case object Mode {

    sealed trait FilterMode
    case object Disabled extends FilterMode
    case object DarkMode extends FilterMode
    case object Enabled extends FilterMode

    val Default: FilterMode = DarkMode

    implicit val param: Stack.Param[Mode] = Stack.Param(Mode(Default))
  }

  /**
   * Creates a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.service.DeadlineFilter]].
   */
  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module4[
      param.Stats,
      DeadlineFilter.RejectPeriod,
      DeadlineFilter.MaxRejectFraction,
      DeadlineFilter.Mode,
      ServiceFactory[Req, Rep]
    ] {
      val role = new Stack.Role("DeadlineFilter")
      val description = "Reject requests when their deadline has passed"

      def make(
        _stats: param.Stats,
        _rejectPeriod: DeadlineFilter.RejectPeriod,
        _maxRejectFraction: DeadlineFilter.MaxRejectFraction,
        mode: DeadlineFilter.Mode,
        next: ServiceFactory[Req, Rep]
      ) =
        mode match {
          case Mode(Mode.DarkMode) | Mode(Mode.Enabled) =>
            val rejectPeriod = _rejectPeriod.rejectPeriod
            val maxRejectFraction = _maxRejectFraction.maxRejectFraction

            if (maxRejectFraction <= 0.0) next
            else {
              val param.Stats(statsReceiver) = _stats
              val scopedStatsReceiver = statsReceiver.scope("admission_control", "deadline")
              val darkMode = mode == Mode(Mode.DarkMode)

              new ServiceFactoryProxy[Req, Rep](next) {

                private[this] val newDeadlineFilter: Service[Req, Rep] => Service[Req, Rep] =
                  service =>
                    new DeadlineFilter(
                      rejectPeriod = rejectPeriod,
                      maxRejectFraction = maxRejectFraction,
                      statsReceiver = scopedStatsReceiver,
                      isDarkMode = darkMode
                    ).andThen(service)

                override def apply(conn: ClientConnection): Future[Service[Req, Rep]] =
                  // Create a DeadlineFilter per connection, so we don't share the state of the token
                  // bucket for rejecting requests.
                  next(conn).map(newDeadlineFilter)
              }
            }

          case _ =>
            next
        }
    }

  class DeadlineExceededException private[DeadlineFilter] (
    timestamp: Time,
    deadline: Time,
    elapsed: Duration,
    now: Time,
    val flags: Long = FailureFlags.DeadlineExceeded
  ) extends Exception(
        s"exceeded request deadline of ${deadline - timestamp} "
          + s"by $elapsed. Deadline expired at $deadline and now it is $now."
      )
      with FailureFlags[DeadlineExceededException]
      with HasLogLevel {
    def logLevel: Level = Level.DEBUG
    protected def copyWithFlags(flags: Long): DeadlineExceededException =
      new DeadlineExceededException(timestamp, deadline, elapsed, now, flags)
  }
}

/**
 * A [[com.twitter.finagle.Filter]] that rejects requests when their deadline
 * has passed.
 *
 * @param rejectPeriod No more than `maxRejectFraction` of requests will be
 *        discarded over the `rejectPeriod`. Must be `>= 1 second` and `<= 60 seconds`.
 *        Default is 10.seconds.
 * @param maxRejectFraction Maximum fraction of requests per-connection that can be
 *        rejected over `rejectPeriod`. Must be between 0.0 and 1.0.
 *        Default is 0.2.
 * @param statsReceiver for stats reporting, typically scoped to
 *        ".../admission_control/deadline/"
 * @param nowMillis current time in milliseconds
 * @param isDarkMode DarkMode will collect stats but not reject requests
 * @see The [[https://twitter.github.io/finagle/guide/Servers.html#request-deadline user guide]]
 *      for more details.
 */
class DeadlineFilter[Req, Rep](
  rejectPeriod: Duration = DeadlineFilter.DefaultRejectPeriod,
  maxRejectFraction: Double = DeadlineFilter.DefaultMaxRejectFraction,
  statsReceiver: StatsReceiver,
  nowMillis: () => Long = Stopwatch.systemMillis,
  isDarkMode: Boolean
) extends SimpleFilter[Req, Rep] {

  def this(
    rejectPeriod: Duration,
    maxRejectFraction: Double,
    statsReceiver: StatsReceiver,
    nowMillis: () => Long
  ) =
    this(rejectPeriod, maxRejectFraction, statsReceiver, nowMillis, false)

  import DeadlineFilter.DeadlineExceededException

  require(
    rejectPeriod.inSeconds >= 1 && rejectPeriod.inSeconds <= 60,
    s"rejectPeriod must be [1 second, 60 seconds]: $rejectPeriod"
  )
  require(
    maxRejectFraction <= 1.0,
    s"maxRejectFraction must be between 0.0 and 1.0: $maxRejectFraction"
  )

  private[this] val exceededStat = statsReceiver.counter("exceeded")
  private[this] val rejectedStat = statsReceiver.counter("rejected")
  private[this] val expiredTimeStat = statsReceiver.stat("expired_ms")

  private[this] val serviceDeposit =
    DeadlineFilter.RejectBucketScaleFactor.toInt
  private[this] val rejectWithdrawal =
    (DeadlineFilter.RejectBucketScaleFactor / maxRejectFraction).toInt

  private[this] val rejectBucket = TokenBucket.newLeakyBucket(rejectPeriod, 0, nowMillis)

  // The request is rejected if the set deadline has expired and there are at least
  // `rejectWithdrawal` tokens in `rejectBucket`. Otherwise, the request is
  // serviced and `serviceDeposit` tokens are added to `rejectBucket`.
  def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {
    Deadline.current match {
      case Some(Deadline(timestamp, deadline)) =>
        val now = Time.now
        val remaining = deadline - now

        if (deadline < now) {
          val exceeded = now - deadline
          exceededStat.incr()
          expiredTimeStat.add(exceeded.inMillis)

          // There are enough tokens to reject the request
          if (rejectBucket.tryGet(rejectWithdrawal)) {
            rejectedStat.incr()
            if (isDarkMode)
              service(request)
            else
              Future.exception(
                new DeadlineExceededException(timestamp, deadline, exceeded, now)
              )
          } else {
            rejectBucket.put(serviceDeposit)
            service(request)
          }
        } else {
          rejectBucket.put(serviceDeposit)
          service(request)
        }
      case None =>
        rejectBucket.put(serviceDeposit)
        service(request)
    }
  }
}
