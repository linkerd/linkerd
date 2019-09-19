package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Service
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.transport.{SimpleTransportContext, Transport, TransportContext}
import com.twitter.util.{Future, Promise, Time}
import io.buoyant.test.FunSuite
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.Queue

class Netty4ServerDispatcherTest extends FunSuite {
  setLogLevel(com.twitter.logging.Level.OFF)

  test("serves multiple concurrent requests ") {
    val recvq = new AsyncQueue[Http2Frame]
    @volatile var sentq = Queue.empty[Http2Frame]
    val closeP = new Promise[Throwable]
    val transport = new Transport[Http2Frame, Http2Frame] {
      type Context = TransportContext

      def context: Context = new SimpleTransportContext()

      def status = ???

      def peerCertificate = None

      def read(): Future[Http2Frame] = recvq.poll()

      def write(f: Http2Frame): Future[Unit] = {
        sentq = sentq :+ f
        Future.Unit
      }

      def onClose = closeP

      def close(d: Time): Future[Unit] = {
        closeP.setValue(new Exception)
        Future.Unit
      }
    }

    val bartmanCalled = new AtomicBoolean(false)
    val bartmanStreamP = new Promise[Stream]

    val elBartoCalled = new AtomicBoolean(false)
    val elBartoStreamP = new Promise[Stream]

    val service = Service.mk[Request, Response] { req =>
      req.authority match {
        case "bartman" if bartmanCalled.compareAndSet(false, true) =>
          bartmanStreamP.map(Response(Status.Cowabunga, _))

        case "elbarto" if elBartoCalled.compareAndSet(false, true) =>
          elBartoStreamP.map(Response(Status.Cowabunga, _))

        case _ =>
          Future.value(Response(Status.EatMyShorts, Stream.empty()))
      }
    }

    val stats = new InMemoryStatsReceiver
    val dispatcher = new Netty4ServerDispatcher(transport, None, service, stats, None)

    assert(!bartmanCalled.get)
    assert(
      recvq.offer(
      {
        val hs = new DefaultHttp2Headers
        hs.scheme("http")
        hs.method("GET")
        hs.authority("bartman")
        hs.path("/")
        val hf = new DefaultHttp2HeadersFrame(hs, true)
        hf.stream(H2FrameStream(3, Http2Stream.State.OPEN))
      }
      )
    )
    eventually {
      assert(bartmanCalled.get)
    }

    assert(!elBartoCalled.get)
    assert(
      recvq.offer(
      {
        val hs = new DefaultHttp2Headers
        hs.scheme("http")
        hs.method("GET")
        hs.authority("elbarto")
        hs.path("/")
        val hf = new DefaultHttp2HeadersFrame(hs, true)
        hf.stream(H2FrameStream(5, Http2Stream.State.HALF_CLOSED_REMOTE))
      }
      )
    )
    eventually {
      assert(elBartoCalled.get)
    }

    assert(sentq.isEmpty)

    val bartmanStream = new AsyncQueue[Frame]
    bartmanStreamP.setValue(Stream(bartmanStream))
    eventually {
      assert(
        sentq.head == {
          val hs = new DefaultHttp2Headers
          hs.status("222")
          val hf = new DefaultHttp2HeadersFrame(hs, false)
          hf.stream(H2FrameStream(3, Http2Stream.State.HALF_CLOSED_REMOTE))
        }
      )
    }
    sentq = sentq.tail

    val elBartoStream = new AsyncQueue[Frame]
    elBartoStreamP.setValue(Stream(elBartoStream))
    eventually {
      assert(
        sentq.head == {
          val hs = new DefaultHttp2Headers
          hs.status("222")
          val hf = new DefaultHttp2HeadersFrame(hs, false)
          hf.stream(H2FrameStream(5, Http2Stream.State.HALF_CLOSED_REMOTE))
        }
      )
    }
    sentq = sentq.tail

    assert(bartmanStream.offer(Frame.Data("0", false)))
    eventually {
      sentq.headOption match {
        case Some(f: Http2DataFrame) =>
          assert(f.stream.id == 3)
          assert(!f.isEndStream)
        case f =>
          fail(s"unexpected frame: $f")
      }
    }
    sentq = sentq.tail

    assert(elBartoStream.offer(Frame.Data("0", true)))
    eventually {
      sentq.headOption match {
        case Some(f: Http2DataFrame) =>
          assert(f.stream.id == 5)
          assert(f.isEndStream)
        case f =>
          fail(s"unexpected frame: $f")
      }
    }
    sentq = sentq.tail

    assert(bartmanStream.offer(Frame.Data("0", true)))
    eventually {
      sentq.headOption match {
        case Some(f: Http2DataFrame) =>
          assert(f.stream.id == 3)
          assert(f.isEndStream)
        case f =>
          fail(s"unexpected frame: $f")
      }
    }
    sentq = sentq.tail
  }

  test("respects maxConcurrentStreams") {
    val recvq = new AsyncQueue[Http2Frame]
    @volatile var sentq = Queue.empty[Http2Frame]
    val closeP = new Promise[Throwable]
    val transport = new Transport[Http2Frame, Http2Frame] {
      type Context = TransportContext

      def context: Context = new SimpleTransportContext()

      def status = ???

      def peerCertificate = None

      def read(): Future[Http2Frame] = recvq.poll()

      def write(f: Http2Frame): Future[Unit] = {
        sentq = sentq :+ f
        Future.Unit
      }

      def onClose = closeP

      def close(d: Time): Future[Unit] = {
        closeP.setValue(new Exception)
        Future.Unit
      }
    }

    val stream1Called = new AtomicBoolean(false)
    val stream1StreamP = new Promise[Stream]

    val service = Service.mk[Request, Response] { req =>
      req.authority match {
        case "stream1" if stream1Called.compareAndSet(false, true) =>
          stream1StreamP.map(Response(Status.Cowabunga, _))

        case _ =>
          Future.value(Response(Status.EatMyShorts, Stream.empty()))
      }
    }

    val stats = new InMemoryStatsReceiver
    val dispatcher = new Netty4ServerDispatcher(
      transport,
      None,
      service,
      stats,
      maxConcurrentStreams = Some(1l)
    )

    assert(!stream1Called.get)
    assert(
      recvq.offer(
      {
        val hs = new DefaultHttp2Headers
        hs.scheme("http")
        hs.method("GET")
        hs.authority("stream1")
        hs.path("/")
        val hf = new DefaultHttp2HeadersFrame(hs, true)
        hf.stream(H2FrameStream(3, Http2Stream.State.OPEN))
      }
      )
    )

    eventually {
      assert(stream1Called.get)
    }
    eventually {
      assert(dispatcher.activeStreams == 1)
    }

    assert(
      recvq.offer(
      {
        val hs = new DefaultHttp2Headers
        hs.scheme("http")
        hs.method("GET")
        hs.authority("stream2")
        hs.path("/")
        val hf = new DefaultHttp2HeadersFrame(hs, true)
        hf.stream(H2FrameStream(5, Http2Stream.State.OPEN))
      }
      )
    )

    // client attempts to initiate a new stream but gets REFUSED_STREAM
    // instead as we already have one active stream
    eventually {
      assert(
        sentq.head == new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM)
          .stream(H2FrameStream(5, Http2Stream.State.OPEN))
      )
    }
    assert(dispatcher.activeStreams == 1)
  }

  test("continue allowing stream creation when active streams count decreases below max allowed") {
    val recvq = new AsyncQueue[Http2Frame]
    @volatile var sentq = Queue.empty[Http2Frame]
    val closeP = new Promise[Throwable]
    val transport = new Transport[Http2Frame, Http2Frame] {
      type Context = TransportContext

      def context: Context = new SimpleTransportContext()

      def status = ???

      def peerCertificate = None

      def read(): Future[Http2Frame] = recvq.poll()

      def write(f: Http2Frame): Future[Unit] = {
        sentq = sentq :+ f
        Future.Unit
      }

      def onClose = closeP

      def close(d: Time): Future[Unit] = {
        closeP.setValue(new Exception)
        Future.Unit
      }
    }

    val stream1Called = new AtomicBoolean(false)
    val stream1StreamP = new Promise[Stream]

    val stream2Called = new AtomicBoolean(false)
    val stream2StreamP = new Promise[Stream]

    val service = Service.mk[Request, Response] { req =>
      req.authority match {
        case "stream1" if stream1Called.compareAndSet(false, true) =>
          stream1StreamP.map(Response(Status.Cowabunga, _))

        case "stream2" if stream2Called.compareAndSet(false, true) =>
          stream2StreamP.map(Response(Status.Cowabunga, _))

        case _ =>
          Future.value(Response(Status.EatMyShorts, Stream.empty()))
      }
    }

    val stats = new InMemoryStatsReceiver
    val dispatcher = new Netty4ServerDispatcher(
      transport,
      None,
      service,
      stats,
      maxConcurrentStreams = Some(1l)
    )

    assert(!stream1Called.get)
    assert(
      recvq.offer(
      {
        val hs = new DefaultHttp2Headers
        hs.scheme("http")
        hs.method("GET")
        hs.authority("stream1")
        hs.path("/")
        val hf = new DefaultHttp2HeadersFrame(hs, true)
        hf.stream(H2FrameStream(3, Http2Stream.State.OPEN))
      }
      )
    )

    eventually {
      assert(stream1Called.get)
    }
    eventually {
      assert(dispatcher.activeStreams == 1)
    }

    val stream2Headers = {
      val hs = new DefaultHttp2Headers
      hs.scheme("http")
      hs.method("GET")
      hs.authority("stream2")
      hs.path("/")
      val hf = new DefaultHttp2HeadersFrame(hs, false)
      hf.stream(H2FrameStream(5, Http2Stream.State.OPEN))
    }

    assert(recvq.offer(stream2Headers))
    // client attempts to initiate a new stream but gets REFUSED_STREAM
    // instead as we already have one active stream
    eventually {
      assert(
        sentq.head == new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM)
          .stream(H2FrameStream(5, Http2Stream.State.OPEN))
      )
    }
    sentq = sentq.tail

    assert(dispatcher.activeStreams == 1)
    eventually {
      assert(!stream2Called.get)
    } // ensure stream 2 switch was not touched

    val stream1 = new AsyncQueue[Frame]
    stream1StreamP.setValue(Stream(stream1))
    eventually {
      assert(
        sentq.head == {
          val hs = new DefaultHttp2Headers
          hs.status("222")
          val hf = new DefaultHttp2HeadersFrame(hs, false)
          hf.stream(H2FrameStream(3, Http2Stream.State.HALF_CLOSED_REMOTE))
        }
      )
    }
    sentq = sentq.tail

    assert(stream1.offer(Frame.Data("0", true)))
    eventually {
      sentq.headOption match {
        case Some(f: Http2DataFrame) =>
          assert(f.stream.id == 3)
          assert(f.isEndStream)
        case f =>
          fail(s"unexpected frame: $f")
      }
    }
    sentq = sentq.tail

    assert(recvq.offer(stream2Headers))
    eventually {
      assert(stream2Called.get)
    } // ensure stream 2 switch was touched
    eventually {
      assert(dispatcher.activeStreams == 1)
    }
  }

}
