package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle
import com.twitter.finagle.Service
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.transport.{LegacyContext, Transport, TransportContext}
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Time}
import io.buoyant.test.FunSuite
import io.netty.handler.codec.http2._
import java.net.SocketAddress
import java.security.cert.Certificate
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
      def context: Context = new LegacyContext(this)
      def status = ???
      def localAddress = new SocketAddress {}
      def remoteAddress = new SocketAddress {}
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
    val dispatcher = new Netty4ServerDispatcher(transport, service, stats)

    assert(!bartmanCalled.get)
    assert(recvq.offer({
      val hs = new DefaultHttp2Headers
      hs.scheme("http")
      hs.method("GET")
      hs.authority("bartman")
      hs.path("/")
      val hf = new DefaultHttp2HeadersFrame(hs, true)
      hf.stream(H2FrameStream(3, Http2Stream.State.OPEN))
    }))
    eventually { assert(bartmanCalled.get) }

    assert(!elBartoCalled.get)
    assert(recvq.offer({
      val hs = new DefaultHttp2Headers
      hs.scheme("http")
      hs.method("GET")
      hs.authority("elbarto")
      hs.path("/")
      val hf = new DefaultHttp2HeadersFrame(hs, true)
      hf.stream(H2FrameStream(5, Http2Stream.State.HALF_CLOSED_REMOTE))
    }))
    eventually { assert(elBartoCalled.get) }

    assert(sentq.isEmpty)

    val bartmanStream = new AsyncQueue[Frame]
    bartmanStreamP.setValue(Stream(bartmanStream))
    eventually {
      assert(sentq.head == {
        val hs = new DefaultHttp2Headers
        hs.status("222")
        val hf = new DefaultHttp2HeadersFrame(hs, false)
        hf.stream(H2FrameStream(3, Http2Stream.State.HALF_CLOSED_REMOTE))
      })
    }
    sentq = sentq.tail

    val elBartoStream = new AsyncQueue[Frame]
    elBartoStreamP.setValue(Stream(elBartoStream))
    eventually {
      assert(sentq.head == {
        val hs = new DefaultHttp2Headers
        hs.status("222")
        val hf = new DefaultHttp2HeadersFrame(hs, false)
        hf.stream(H2FrameStream(5, Http2Stream.State.HALF_CLOSED_REMOTE))
      })
    }
    sentq = sentq.tail

    assert(bartmanStream.offer(Frame.Data(Buf.Utf8("0"), false)))
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

    assert(elBartoStream.offer(Frame.Data(Buf.Utf8("0"), true)))
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

    assert(bartmanStream.offer(Frame.Data(Buf.Utf8("0"), true)))
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
}
