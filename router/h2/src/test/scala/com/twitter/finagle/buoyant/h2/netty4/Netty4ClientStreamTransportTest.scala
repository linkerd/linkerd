package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.BufAsByteBuf
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise}
import io.buoyant.test.Awaits
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http2._
import org.scalatest.FunSuite
import scala.collection.immutable.Queue

class Netty4ClientStreamTransportTest extends FunSuite with Awaits {

  test("writeHeaders") {
    val id = 4
    var frame: Option[Http2StreamFrame] = None
    val writer = new Netty4H2Writer {
      def write(f: Http2StreamFrame) = {
        frame = Some(f)
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val stream = new Netty4ClientStreamTransport(id, writer, Int.MaxValue, stats)

    val headers: Headers = {
      val hs = new DefaultHttp2Headers
      hs.scheme("http")
      hs.method("get")
      hs.path("/")
      hs.authority("a")
      Netty4Message.Headers(hs)
    }
    val w = stream.writeHeaders(headers, true)
    assert(w.isDefined)
    frame match {
      case Some(f: Http2HeadersFrame) =>
        assert(f.isEndStream)
        assert(f.headers.scheme() == "http")
        assert(f.headers.method() == "get")
        assert(f.headers.path() == "/")
        assert(f.headers.authority() == "a")
      case Some(f) => fail(s"unexpected frame: ${f.name}")
      case None => fail("frame not written")
    }
  }

  test("writeStream") {
    val id = 6
    var recvq = Queue.empty[Http2StreamFrame]
    val writer = new Netty4H2Writer {
      def write(f: Http2StreamFrame) = {
        recvq = recvq :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val stream = new Netty4ClientStreamTransport(id, writer, Int.MaxValue, stats)

    val sendq = new AsyncQueue[Frame]
    val endP = new Promise[Unit]
    val data = new Stream.Reader {
      def onEnd = endP
      def read() = sendq.poll()
      def reset(exn: Throwable) = ???
    }
    val w = stream.writeStream(data)
    assert(!w.isDefined)

    val heyo = Buf.Utf8("heyo")
    sendq.offer(new Frame.Data {
      def isEnd = false
      def buf = heyo
      def release() = Future.Unit
    })
    assert(recvq.head ==
      new DefaultHttp2DataFrame(BufAsByteBuf.Owned(heyo), false).setStreamId(id))
    recvq = recvq.tail

    sendq.offer(new Frame.Data {
      def isEnd = true
      def buf = heyo
      def release() = Future.Unit
    })
    assert(recvq.head ==
      new DefaultHttp2DataFrame(BufAsByteBuf.Owned(heyo), true).setStreamId(id))
    recvq = recvq.tail
  }

  test("readResponse, no accumulation") {
    val id = 8
    var recvq = Queue.empty[Http2StreamFrame]
    val writer = new Netty4H2Writer {
      def write(f: Http2StreamFrame) = ???
    }
    val stats = new InMemoryStatsReceiver
    val stream = new Netty4ClientStreamTransport(id, writer, Int.MaxValue, stats)

    val rspf = stream.readResponse()
    assert(!rspf.isDefined)

    stream.offer(new DefaultHttp2HeadersFrame({
      val hs = new DefaultHttp2Headers
      hs.status("222")
      hs
    }))

    assert(rspf.isDefined)
    val rsp = await(rspf)
    val data = rsp.data match {
      case Stream.Nil => fail("empty stream")
      case r: Stream.Reader => r
    }
    val endf = data.onEnd
    assert(!endf.isDefined)
    assert(rsp.status == Status.Cowabunga)

    val dataf = data.read()
    assert(!dataf.isDefined)

    val buf = Buf.Utf8("space ghost coast to coast")
    stream.offer(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.offer({
      val hs = new DefaultHttp2Headers
      hs.set("trailers", "yea")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    })
    assert(dataf.isDefined)
    await(dataf) match {
      case data: Frame.Data =>
        assert(data.buf == buf)
      case frame =>
        fail(s"unexpected frame: $frame")
    }

    val trailf = data.read()
    assert(trailf.isDefined)
    await(trailf) match {
      case trailers: Frame.Trailers =>
        assert(trailers.toSeq == Seq("trailers" -> "yea"))
      case frame =>
        fail(s"unexpected frame: $frame")
    }
  }

  test("readResponse, with accumulation") {
    val id = 8
    var recvq = Queue.empty[Http2StreamFrame]
    val writer = new Netty4H2Writer {
      def write(f: Http2StreamFrame) = ???
    }
    val stats = new InMemoryStatsReceiver
    val stream = new Netty4ClientStreamTransport(id, writer, 2, stats)

    val rspf = stream.readResponse()
    assert(!rspf.isDefined)

    stream.offer(new DefaultHttp2HeadersFrame({
      val hs = new DefaultHttp2Headers
      hs.status("222")
      hs
    }))

    assert(rspf.isDefined)
    val rsp = await(rspf)
    val data = rsp.data match {
      case Stream.Nil => fail("empty stream")
      case data: Stream.Reader => data
    }
    val endf = data.onEnd
    assert(!endf.isDefined)
    assert(rsp.status == Status.Cowabunga)

    val dataf0 = data.read()
    assert(!dataf0.isDefined)

    val buf = Buf.Utf8("space ghost coast to coast")
    stream.offer(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.offer(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.offer(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.offer({
      val hs = new DefaultHttp2Headers
      hs.set("trailers", "yea")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    })

    assert(dataf0.isDefined)
    await(dataf0) match {
      case data: Frame.Data =>
        assert(data.buf == buf)
      case frame =>
        fail(s"unexpected frame: $frame")
    }

    val dataf1 = data.read()
    assert(dataf1.isDefined)
    await(dataf1) match {
      case data: Frame.Data =>
        assert(data.buf == buf.concat(buf))
      case frame =>
        fail(s"unexpected frame: $frame")
    }

    val trailf = data.read()
    assert(trailf.isDefined)
    await(trailf) match {
      case trailers: Frame.Trailers =>
        assert(trailers.toSeq == Seq("trailers" -> "yea"))
      case frame =>
        fail(s"unexpected frame: $frame")
    }
  }
}
