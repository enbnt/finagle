package com.twitter.finagle.dispatch

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.util._
import com.twitter.finagle.context.{Contexts, RemoteInfo}
import com.twitter.finagle.transport.Transport
import java.net.SocketAddress
import java.security.cert.X509Certificate
import org.junit.runner.RunWith
import org.mockito.Mockito.{atLeastOnce, never, times, verify, when}
import org.mockito.Matchers.any
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.mockito.MockitoSugar
import scala.language.reflectiveCalls

@RunWith(classOf[JUnitRunner])
class SerialServerDispatcherTest extends FunSuite with MockitoSugar {

  trait Ctx {
    val trans = mock[Transport[String, String]]
    when(trans.peerCertificate).thenReturn(None)
    when(trans.onClose).thenReturn(Future.never)
    val readp = new Promise[String]
    when(trans.read()).thenReturn(readp)
    val writep = new Promise[Unit]
    when(trans.write(any[String])).thenReturn(writep)
  }

  test("Dispatch one at a time")(new Ctx {
    val service = mock[Service[String, String]]
    when(service.close(any[Time])).thenReturn(Future.Done)
    val disp = new SerialServerDispatcher(trans, service)

    verify(trans).read()
    verify(trans, never()).write(any[String])
    verify(service, never()).apply(any[String])

    val servicep = new Promise[String]
    when(service(any[String])).thenReturn(servicep)

    readp.setValue("ok")
    verify(service).apply("ok")
    verify(trans, never()).write(any[String])

    servicep.setValue("ack")
    verify(trans).write("ack")

    verify(trans).read()

    when(trans.read()).thenReturn(new Promise[String]) // to short circuit
    writep.setDone()

    verify(trans, times(2)).read()
  })

  test("Inject the transport certificate if present")(new Ctx {
    val mockCert = mock[X509Certificate]
    when(trans.peerCertificate).thenReturn(Some(mockCert))
    val service = new Service[String, String] {
      override def apply(request: String): Future[String] = Future.value {
        if (Contexts.local.get(Transport.peerCertCtx) == Some(mockCert)) "ok" else "not ok"
      }
    }

    val disp = new SerialServerDispatcher(trans, service)

    readp.setValue("go")
    verify(trans).write("ok")
  })

  test("Inject the transport remote address")(new Ctx {
    val mockAddr = mock[SocketAddress]
    when(trans.remoteAddress).thenReturn(mockAddr)
    val service = new Service[String, String] {
      override def apply(request: String): Future[String] = Future.value {
        if (Contexts.local.get(RemoteInfo.Upstream.AddressCtx) == Some(mockAddr)) "ok" else "not ok"
      }
    }

    val disp = new SerialServerDispatcher(trans, service)

    readp.setValue("go")
    verify(trans).write("ok")
  })

  test("Clear and delimit com.twitter.util.Local")(new Ctx {
    val l = new Local[String]
    var ncall = 0

    val s = new Service[String, String] {
      def apply(req: String) = {
        ncall += 1
        val prev = l() getOrElse "undefined"
        l() = req
        Future.value(prev)
      }
    }

    l() = "orig"
    val disp = new SerialServerDispatcher(trans, s)

    readp.setValue("blah")
    assert(ncall == 1)
    assert(l() == Some("orig"))
    verify(trans).write("undefined")
  })

  def getMockTrans(
    onClose: Promise[Throwable],
    writep: Promise[Unit]
  ): Transport[String, String] = {
    val trans = mock[Transport[String, String]]
    when(trans.write(any[String])).thenReturn(writep)
    when(trans.onClose).thenReturn(onClose)
    when(trans.close).thenReturn(onClose.unit)
    when(trans.close(any[Time])).thenReturn(onClose.unit)
    when(trans.peerCertificate).thenReturn(None)
    trans
  }

  trait Ictx {
    val onClose = new Promise[Throwable]
    val writep = new Promise[Unit]
    val trans = getMockTrans(onClose, writep)

    val service = mock[Service[String, String]]
    when(service.close(any[Time])).thenReturn(Future.Done)
    val replyp = new Promise[String] {
      @volatile var interrupted: Option[Throwable] = None
      setInterruptHandler { case exc => interrupted = Some(exc) }
    }
    when(service("ok")).thenReturn(replyp)

    val readp = new Promise[String]
    when(trans.read()).thenReturn(readp)

    val disp = new SerialServerDispatcher(trans, service)
  }

  test("interrupt on hangup: while pending")(new Ictx {
    readp.setValue("ok")
    verify(service).apply("ok")
    assert(!replyp.interrupted.isDefined)
    onClose.setValue(new Exception)
    assert(replyp.interrupted.isDefined)
  })

  test("interrupt on hangup: while reading")(new Ictx {
    verify(trans).read()
    onClose.setValue(new Exception)
    assert(!replyp.interrupted.isDefined)
    verify(service, times(0)).apply(any[String])
    readp.setValue("ok")
    verify(service, times(0)).apply(any[String])
    // This falls through.
    verify(trans, atLeastOnce()).close()
    verify(service).close(any[Time])
  })

  test("interrupt on hangup: while draining")(new Ictx {
    readp.setValue("ok")
    verify(service).apply("ok")
    replyp.setValue("yes")
    disp.close(Time.now)
    assert(!replyp.interrupted.isDefined)
    verify(trans).write("yes")
    onClose.setValue(new Exception)
    assert(!replyp.interrupted.isDefined)
  })

  trait Dctx {
    val onClose = new Promise[Throwable]
    val writep = new Promise[Unit]
    val trans = getMockTrans(onClose, writep)

    when(trans.write(any[String])).thenReturn(writep)

    val service = mock[Service[String, String]]
    when(service.close(any[Time])).thenReturn(Future.Done)

    val readp = new Promise[String]
    when(trans.read()).thenReturn(readp)

    val disp = new SerialServerDispatcher(trans, service)

    verify(trans).read()
  }

  test("isClosing")(new Ictx {
    assert(!disp.isClosing)
    disp.close(Time.now)
    assert(disp.isClosing)
  })

  test("drain: while reading")(new Dctx {
    disp.close(Time.now)
    verify(trans).close(any[Time])
    verify(service, times(0)).close(any[Time])

    readp.setException(new Exception("closed!"))
    onClose.setValue(new Exception("closed!"))
    verify(service).close(any[Time])
    verify(service, times(0)).apply(any[String])
    verify(trans, times(0)).write(any[String])
    verify(trans).read()
  })

  trait TimerCtx {
    val onClose = new Promise[Throwable]
    val writep = new Promise[Unit]
    val trans = getMockTrans(onClose, writep)

    when(trans.write(any[String])).thenReturn(writep)

    val service = mock[Service[String, String]]
    when(service.close(any[Time])).thenReturn(Future.Done)

    val readp = new Promise[String]
    when(trans.read()).thenReturn(readp)

    val mockTimer = new MockTimer
    val disp = new SerialServerDispatcher(trans, service) {
      override private[dispatch] def timer: Timer = mockTimer
    }

    verify(trans).read()
  }

  test("drain: while dispatching")(new TimerCtx {
    val servicep = new Promise[String]
    when(service(any[String])).thenReturn(servicep)
    readp.setValue("ok")
    verify(service).apply("ok")

    Time.withCurrentTimeFrozen { _ =>
      val closeTime = Time.now + 5.seconds // will never reach this time
      disp.close(closeTime)

      assert(mockTimer.tasks.length == 1)
      assert(mockTimer.tasks(0).when == closeTime)

      verify(service, times(0)).close(any[Time])
      verify(trans, times(0)).close()

      servicep.setValue("yes")
      verify(trans).write("yes")
      verify(service, times(0)).close(any[Time])
      verify(trans, times(0)).close()

      writep.setDone()
      verify(trans).close()
      onClose.setValue(new Exception("closed!"))
      verify(service).close(any[Time])
    }
  })

  test("drain: missing the deadline forces close")(new TimerCtx {
    val servicep = new Promise[String]
    when(service(any[String])).thenReturn(servicep)
    readp.setValue("ok")
    verify(service).apply("ok")

    // Close the transport now
    Time.withCurrentTimeFrozen { control =>
      val deadline = Time.now
      disp.close(deadline)
      assert(mockTimer.tasks.length == 1)
      assert(mockTimer.tasks(0).when == Time.now)

      verify(service, times(0)).close(any[Time])
      verify(trans, times(0)).close()

      // Timeout
      control.advance(1.second)
      mockTimer.tick()

      verify(trans).close(deadline)

      onClose.setValue(new Exception("closed!"))

      verify(service).close(any[Time])
      assert(servicep.isInterrupted.isDefined)
    }
  })
}
