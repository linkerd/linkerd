package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.Service
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.util.{Future, Promise, Time}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.scalatest.FunSuite

class ServerDispatcherTest extends FunSuite {

  test("reads requests, writes responses, and closes") {
    val streamClosing = new AtomicBoolean(false)
    val streamCloseP = new Promise[Throwable]

    val streamReading = new AtomicBoolean(false)
    val streamReadP = new Promise[Request]

    val streamWriting = new AtomicReference[Response](null)
    val streamWritingP = new Promise[Future[Unit]]
    val streamWroteP = new Promise[Unit]

    val stream = new ServerStreamTransport {
      def close(d: Time) = {
        streamClosing.set(true)
        streamCloseP.unit
      }
      def onClose = streamCloseP
      def read() = {
        streamReading.set(true)
        streamReadP
      }
      def write(rsp: Response): Future[Future[Unit]] = {
        streamWriting.set(rsp)
        streamWritingP
      }
    }

    val serviceClosing = new AtomicBoolean(false)
    val serviceCloseP = new Promise[Unit]

    val servicingReq = new AtomicReference[Request](null)
    val serviceRspP = new Promise[Response]

    val service = new Service[Request, Response] {
      override def close(d: Time): Future[Unit] = {
        serviceClosing.set(true)
        serviceCloseP
      }
      def apply(req: Request): Future[Response] = {
        servicingReq.set(req)
        serviceRspP
      }
    }

    assert(!streamClosing.get)
    assert(!streamReading.get)
    assert(!serviceClosing.get)
    assert(streamWriting.get == null)
    assert(servicingReq.get == null)

    val stats = new InMemoryStatsReceiver
    val dispatcher = new ServerDispatcher(stream, service, stats.scope("scope"))

    assert(!streamClosing.get)
    assert(streamReading.get)
    assert(!serviceClosing.get)
    assert(streamWriting.get == null)
    assert(servicingReq.get == null)

    val req = new Request {
      val scheme = "http"
      val method = "get"
      val authority = "authoirtah"
      val path = "/"
      val isEmpty = true
      val headers = Nil
      def onEnd = Future.Unit
      def read() = Future.never
      def fail(e: Throwable): Unit = {}
    }
    streamReadP.setValue(req)

    assert(!streamClosing.get)
    assert(streamReading.get)
    assert(!serviceClosing.get)
    assert(streamWriting.get == null)
    assert(servicingReq.get == req)

  }
}
