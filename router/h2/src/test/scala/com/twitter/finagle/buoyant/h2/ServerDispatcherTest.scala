package com.twitter.finagle.buoyant.h2

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.stats.{InMemoryStatsReceiver, BucketAndCount}
import com.twitter.io.Buf
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

      val reqEndP = new Promise[Unit]
      val req: Request = new Request {
        override val scheme = "http"
        override val method = Method.Get
        override val authority = "authoirtah"
        override val path = "/"
        override val headers: Headers = new Headers {
          def toSeq = Nil
          def get(k: String) = Nil
          def contains(k: String) = false
          def add(k: String, v: String) = {}
          def set(k: String, v: String) = {}
          def remove(k: String) = Nil
          def dup() = this
        }
        override val data: Stream = new Stream.Reader {
          def onEnd = reqEndP
          def read() = reqEndP.map { _ =>
            new Frame.Data {
              val buf = Buf.Empty
              def release() = Future.Unit
              def isEnd = true
            }
          }
          def reset(e: Throwable): Unit = {}
        }
        def dup() = this
      }
      streamReadP.setValue(req)

      clock.advance(10.millisecond)

      assert(servicingReq.get == req)
      assert(streamWriting.get == null)
      assert(serviceClosing.get == false)
      assert(streamClosing.get == false)
      assert(stats.histogramDetails.size == 0)
      clock.advance(10.millisecond)

      val rspEndP = new Promise[Unit]
      val rsp: Response = new Response {
        override def status = Status.Ok
        override val headers: Headers = new Headers {
          override def toSeq = Nil
          override def get(k: String) = Nil
          override def contains(k: String) = false
          override def add(k: String, v: String) = {}
          override def set(k: String, v: String) = {}
          override def remove(k: String) = Nil
          override def dup() = this
        }
        override val data: Stream = new Stream.Reader {
          override def onEnd = rspEndP
          override def read() = Future.never
          override def reset(e: Throwable): Unit = {}
        }
        override def dup() = this
      }
      serviceRspP.setValue(rsp)

      assert(streamWriting.get == rsp)
      assert(serviceClosing.get == false)
      assert(streamClosing.get == false)
      assert(stats.histogramDetails.size == 1)
      assert(stats.histogramDetails.contains("serve_ms"))
      assert(stats.histogramDetails("serve_ms").counts == Seq(BucketAndCount(20, 21, 1)))

      clock.advance(10.millisecond)
      reqEndP.setDone()
      assert(stats.histogramDetails.size == 2)
      assert(stats.histogramDetails.contains("read_ms"))
      assert(stats.histogramDetails("read_ms").counts == Seq(BucketAndCount(40, 41, 1)))
      assert(stats.histogramDetails.contains("serve_ms"))
      assert(stats.histogramDetails("serve_ms").counts == Seq(BucketAndCount(20, 21, 1)))

      clock.advance(10.millisecond)
      streamWritingP.setValue(rspEndP)
      assert(serviceClosing.get == false)
      assert(streamClosing.get == false)

      clock.advance(10.millisecond)
      rspEndP.setDone()
      assert(stats.histogramDetails.size == 3)
      assert(stats.histogramDetails.contains("read_ms"))
      assert(stats.histogramDetails("read_ms").counts == Seq(BucketAndCount(40, 41, 1)))
      assert(stats.histogramDetails.contains("write_ms"))
      assert(stats.histogramDetails("write_ms").counts == Seq(BucketAndCount(30, 31, 1)))
      assert(stats.histogramDetails.contains("serve_ms"))
      assert(stats.histogramDetails("serve_ms").counts == Seq(BucketAndCount(20, 21, 1)))
      eventually {
        assert(serviceClosing.get == true)
      }
      // stream.close() isn't actally called, we just wait on its onClose to fire...
      assert(streamClosing.get == false)
      assert(stats.histogramDetails.size == 3)

      clock.advance(10.millisecond)
      serviceCloseP.setDone()
      streamCloseP.setValue(new Exception)

      assert(stats.histogramDetails.size == 4)
      assert(stats.histogramDetails.contains("active_ms"))
      assert(stats.histogramDetails("active_ms").counts == Seq(BucketAndCount(70, 71, 1)))
    }
  }
}
