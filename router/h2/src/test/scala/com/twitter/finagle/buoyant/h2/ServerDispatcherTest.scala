package com.twitter.finagle.buoyant.h2

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.stats.{InMemoryStatsReceiver, BucketAndCount}
import com.twitter.util.{Future, Promise, Time}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually

class ServerDispatcherTest extends FunSuite with Eventually {

  test("reads requests, writes responses, and closes an empty request/response") {
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

    Time.withCurrentTimeFrozen { clock =>
      assert(streamReading.get == false)
      assert(servicingReq.get == null)
      assert(streamWriting.get == null)
      assert(serviceClosing.get == false)
      assert(streamClosing.get == false)

      val stats = new InMemoryStatsReceiver
      val dispatcher = new ServerDispatcher(stream, service, stats)

      assert(streamReading.get == true)
      assert(servicingReq.get == null)
      assert(streamWriting.get == null)
      assert(serviceClosing.get == false)
      assert(streamClosing.get == false)

      clock.advance(10.millisecond)

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

      clock.advance(10.millisecond)

      assert(servicingReq.get == req)
      assert(streamWriting.get == null)
      assert(serviceClosing.get == false)
      assert(streamClosing.get == false)

      clock.advance(10.millisecond)

      val rsp = new Response {
        val status = 200
        val isEmpty = true
        val headers = Nil
        def onEnd = Future.Unit
        def read() = Future.never
        def fail(e: Throwable): Unit = {}
      }
      serviceRspP.setValue(rsp)

      clock.advance(10.millisecond)

      assert(streamWriting.get == rsp)
      assert(serviceClosing.get == false)
      assert(streamClosing.get == false)

      streamWritingP.setValue(streamWroteP)
      assert(serviceClosing.get == false)
      assert(streamClosing.get == false)

      clock.advance(10.millisecond)

      streamWroteP.setDone()
      assert(stats.histogramDetails.size == 1)
      assert(stats.histogramDetails.contains("streaming_ms"))
      assert(stats.histogramDetails("streaming_ms").counts == Seq(BucketAndCount(10, 11, 1)))
      eventually {
        assert(serviceClosing.get == true)
      }
      // stream.close() isn't actally called, we just wait on its onClose to fire...
      assert(streamClosing.get == false)
      assert(stats.histogramDetails.size == 1)

      clock.advance(10.millisecond)
      serviceCloseP.setDone()
      streamCloseP.setValue(new Exception)

      assert(stats.histogramDetails.contains("serving_ms"))
      assert(stats.histogramDetails("serving_ms").counts == Seq(BucketAndCount(60, 61, 1)))
    }
  }
}
