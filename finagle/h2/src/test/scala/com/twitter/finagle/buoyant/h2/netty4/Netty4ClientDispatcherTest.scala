package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2.Frame.Data.NoopRelease
import com.twitter.finagle.{Status => SvcStatus}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.transport.{SimpleTransportContext, Transport, TransportContext}
import com.twitter.util.{Future, Promise, Time}
import io.buoyant.test.FunSuite
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http2._
import java.nio.charset.StandardCharsets

class Netty4ClientDispatcherTest extends FunSuite {
  setLogLevel(com.twitter.logging.Level.OFF)


  def withDispatcher(initialFrames: Http2Frame*)
    (f: (AsyncQueue[Http2Frame], AsyncQueue[Http2Frame], Netty4ClientDispatcher) => Any) = {

    val recvq, sentq = new AsyncQueue[Http2Frame]
    initialFrames.foreach(recvq.offer)
    val closeP = new Promise[Throwable]
    val transport = new Transport[Http2Frame, Http2Frame] {
      type Context = TransportContext

      def context: Context = new SimpleTransportContext()

      def status = ???

      def peerCertificate = ???

      def read(): Future[Http2Frame] = recvq.poll()

      def write(f: Http2Frame): Future[Unit] = {
        sentq.offer(f)
        Future.Unit
      }

      def onClose = closeP

      def close(d: Time): Future[Unit] = {
        closeP.setValue(new Exception)
        Future.Unit
      }
    }

    val stats = new InMemoryStatsReceiver
    val dispatcher = new Netty4ClientDispatcher(transport, None, stats)
    assert(dispatcher.status == SvcStatus.Open)

    f(recvq, sentq, dispatcher)

    await(transport.close())
    assert(dispatcher.status == SvcStatus.Closed)
  }

  test("dispatches multiple concurrent requests on underlying transport") {
    withDispatcher() { (recvq, sentq, dispatcher) =>

      var released = 0

      def releaser: Int => Future[Unit] = { bytes =>
        released += bytes
        Future.Unit
      }

      // Issue req0
      val req0q = new AsyncQueue[Frame]
      val req0EndP = new Promise[Unit]
      val req0 = {
        val hs = new DefaultHttp2Headers
        hs.scheme("http")
        hs.method("sup")
        hs.path("/")
        hs.authority("auf")
        val stream = new Stream {
          override val isEmpty = false

          override def onEnd = req0EndP

          override def read() = req0q.poll()

          override def cancel(reset: Reset): Unit = ???

          override def onCancel: Future[Reset] = Future.never
        }
        Netty4Message.Request(hs, stream)
      }
      val rsp0f = dispatcher(req0)
      assert(!rsp0f.isDefined)

      val req1 = {
        val hs = new DefaultHttp2Headers
        hs.scheme("http")
        hs.method("sup")
        hs.path("/")
        hs.authority("auf")
        Netty4Message.Request(hs, Stream.empty())
      }
      val rsp1f = dispatcher(req1)
      assert(!rsp1f.isDefined)

      // Initial headers were sent to the server for req0
      val req0InitF = sentq.poll()
      assert(req0InitF.isDefined)
      await(req0InitF) match {
        case hf: Http2HeadersFrame =>
          assert(hf.headers.method == "sup")
          assert(hf.stream.id == 3)
        case f =>
          fail(s"unexpected frame: $f")
      }

      // Initial headers were sent to the server for req1
      val req1InitF = sentq.poll()
      assert(req1InitF.isDefined)
      await(req1InitF) match {
        case hf: Http2HeadersFrame =>
          assert(hf.headers.method == "sup")
          assert(hf.stream.id == 5)
        case f =>
          fail(s"unexpected frame: $f")
      }

      assert(
        req0q.offer(
        {
          val buf = Unpooled.copiedBuffer("how's it goin?", StandardCharsets.UTF_8)
          val sz = buf.readableBytes()
          Frame.Data(buf, false, () => releaser(sz))
        }
        )
      )

      // We receive a response for req1 first:
      assert(
        recvq.offer(
        {
          val hs = new DefaultHttp2Headers
          hs.status("222")
          new DefaultHttp2HeadersFrame(hs, false).stream(H2FrameStream(5, Http2Stream.State.OPEN))
        }
        )
      )

      assert(rsp0f.poll == None)
      assert(rsp1f.isDefined)
      val rsp1 = await(rsp1f)
      assert(rsp1.status == Status.Cowabunga)

      // We receive a response for req0 second:
      assert(
        recvq.offer(
        {
          val hs = new DefaultHttp2Headers
          hs.status("222")
          new DefaultHttp2HeadersFrame(hs, false).stream(H2FrameStream(3, Http2Stream.State.OPEN))
        }
        )
      )
      assert(rsp0f.isDefined)
      val rsp0 = await(rsp0f)
      assert(rsp0.status == Status.Cowabunga)
      assert(rsp0.stream.nonEmpty)

      assert(
        recvq.offer(
        {
          val buf = Unpooled.copiedBuffer("sup", StandardCharsets.UTF_8)
          val dataFrame = new DefaultHttp2DataFrame(buf, true)
          dataFrame.stream(H2FrameStream(3, Http2Stream.State.OPEN))
        }
        )
      )
      assert(
        recvq.offer(
        {
          val buf = Unpooled.copiedBuffer("yo", StandardCharsets.UTF_8)
          val headersFrame = new DefaultHttp2DataFrame(buf, true)
          headersFrame.stream(H2FrameStream(5, Http2Stream.State.OPEN))
        }
        )
      )

      val d0f = rsp0.stream.read()
      assert(d0f.isDefined)

      val d1f = rsp1.stream.read()
      assert(d1f.isDefined)

      await(d0f) match {
        case f: Frame.Data =>
          assert(f.buf.toString(StandardCharsets.UTF_8) == "sup")
          assert(f.isEnd)
          await(f.release())
        case f =>
          fail(s"unexpected frame: $f")
      }
      assert(rsp0.stream.onEnd.isDefined)

      await(d1f) match {
        case f: Frame.Data =>
          assert(f.buf.toString(StandardCharsets.UTF_8) == "yo")
          assert(f.isEnd)
          await(f.release())
        case f =>
          fail(s"unexpected frame: $f")
      }
      assert(rsp1.stream.onEnd.isDefined)


    }
  }

  test("do not accept requests if they go over the max streams allowed by remote") {
    val settingsFrame = new DefaultHttp2SettingsFrame(new Http2Settings().maxConcurrentStreams(2l))

    withDispatcher(settingsFrame) { (recvq, sentq, dispatcher) =>
      eventually {
        assert(dispatcher.maxAllowedStreams.contains(2l))
        ()
      }

      val resp0f = dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty()))
      val resp1f = dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty()))
      assert(!resp0f.isDefined)
      assert(!resp1f.isDefined)

      val resp2 = await(dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty())))
      assert(resp2.status == Status.TooManyRequests)
    }

  }

  test("continue accepting requests when open streams count decreases below max allowed") {
    val settingsFrame = new DefaultHttp2SettingsFrame(new Http2Settings().maxConcurrentStreams(2l))

    withDispatcher(settingsFrame) { (recvq, sentq, dispatcher) =>
      eventually {
        assert(dispatcher.maxAllowedStreams.contains(2l))
        ()
      }

      // Issue req0
      val req0q = new AsyncQueue[Frame]
      val req0 = {
        val hs = new DefaultHttp2Headers
        hs.scheme("http")
        hs.method("sup")
        hs.path("/")
        hs.authority("auf")
        val stream = new Stream {
          override val isEmpty = false

          override def onEnd = new Promise[Unit]

          override def read() = req0q.poll()

          override def cancel(reset: Reset): Unit = ???

          override def onCancel: Future[Reset] = Future.never
        }
        Netty4Message.Request(hs, stream)
      }
      val rsp0f = dispatcher(req0)
      assert(!rsp0f.isDefined)

      val rsp1f = dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty()))
      assert(!rsp1f.isDefined)

      // too many active streams at the moment
      val rejectedResp = await(
        dispatcher(
          Netty4Message
            .Request(new DefaultHttp2Headers, Stream.empty())
        )
      )
      assert(rejectedResp.status == Status.TooManyRequests)
      assert(dispatcher.activeStreams == 2) // currently two streams open

      // we transition into SendCLosed here by sending an EOS
      assert(
        req0q.offer(
        {
          val buf = Unpooled.copiedBuffer("first and last word", StandardCharsets.UTF_8)
          val sz = buf.readableBytes()
          Frame.Data(buf, eos = true, NoopRelease)
        }
        )
      )

      assert(dispatcher.activeStreams == 2) // still two streams open as we are in HALF_CLOSED

      // We receive a response for req0 second and end the stream with EOS
      // This should transition the stream to fully closed and make room for
      // another request
      assert(
        recvq.offer(
        {
          val hs = new DefaultHttp2Headers
          hs.status("222")
          new DefaultHttp2HeadersFrame(hs, true).stream(H2FrameStream(3, Http2Stream.State.OPEN))
        }
        )
      )
      assert(rsp0f.isDefined)
      val rsp0 = await(rsp0f)
      assert(rsp0.status == Status.Cowabunga)
      assert(rsp0.stream.onEnd.isDefined)

      assert(dispatcher.activeStreams == 1) // only one stream left open

      // request will not error out immediately
      val undefinedResp = dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty()))
      assert(!undefinedResp.isDefined)

      assert(dispatcher.activeStreams == 2) // now we have two active streams
    }
  }


  test("reflect remote update to maximum concurrent streams") {
    val settingsFrame1 = new DefaultHttp2SettingsFrame(new Http2Settings().maxConcurrentStreams(2l))
    val settingsFrame2 = new DefaultHttp2SettingsFrame(new Http2Settings().maxConcurrentStreams(3l))

    withDispatcher(settingsFrame1) { (recvq, sentq, dispatcher) =>
      eventually {
        assert(dispatcher.maxAllowedStreams.contains(2l))
        ()
      }

      val resp0f = dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty()))
      val resp1f = dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty()))
      assert(!resp0f.isDefined)
      assert(!resp1f.isDefined)

      val resp2 = await(dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty())))
      assert(resp2.status == Status.TooManyRequests)
      assert(dispatcher.activeStreams == 2)

      // remote sends updated settings with new
      // values for maximum allowed streams
      assert(recvq.offer(settingsFrame2))
      eventually {
        assert(dispatcher.maxAllowedStreams.contains(3l))
        ()
      }

      dispatcher(Netty4Message.Request(new DefaultHttp2Headers, Stream.empty()))
      eventually {
        assert(dispatcher.activeStreams == 3)
        ()
      }

    }
  }
}
