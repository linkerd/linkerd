package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.BufAsByteBuf
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util.{Future, Promise, Time}
import io.buoyant.test.FunSuite
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http2._
import scala.collection.immutable.Queue

class Netty4StreamTransportTest extends FunSuite {
  setLogLevel(Level.OFF)

  test("client: request writeHeaders") {
    val id = 4
    var frame: Option[Http2Frame] = None
    val writer = new Netty4H2Writer {
      def close(d: Time) = ???
      def write(f: Http2Frame) = {
        frame = Some(f)
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val stream = Netty4StreamTransport.client(id, writer, Int.MaxValue, stats)

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

  test("client: request writeStream") {
    val id = 6
    var writeq = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      def close(d: Time) = ???
      def write(f: Http2Frame) = {
        writeq = writeq :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val stream = Netty4StreamTransport.client(id, writer, Int.MaxValue, stats)

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
    sendq.offer(Frame.Data(heyo, false))
    assert(writeq.head ==
      new DefaultHttp2DataFrame(BufAsByteBuf.Owned(heyo), false).setStreamId(id))
    writeq = writeq.tail

    sendq.offer(Frame.Data(heyo, true))
    assert(writeq.head ==
      new DefaultHttp2DataFrame(BufAsByteBuf.Owned(heyo), true).setStreamId(id))
    writeq = writeq.tail
  }

  test("client: response without accumulation") {
    val id = 8
    val writer = new Netty4H2Writer {
      def close(d: Time) = ???
      def write(f: Http2Frame) = Future.Unit
    }
    val stats = new InMemoryStatsReceiver
    val stream = Netty4StreamTransport.client(id, writer, Int.MaxValue, stats)

    val rspf = stream.remote
    assert(!rspf.isDefined)

    stream.offerRemote(new DefaultHttp2HeadersFrame({
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
    stream.offerRemote(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.offerRemote({
      val hs = new DefaultHttp2Headers
      hs.set("trailers", "yea")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    })
    assert(dataf.isDefined)
    await(dataf) match {
      case data: Frame.Data =>
        assert(data.buf == buf)
        await(data.release())
      case frame =>
        fail(s"unexpected frame: $frame")
    }

    val trailf = data.read()
    assert(trailf.isDefined)
    await(trailf) match {
      case trailers: Frame.Trailers =>
        assert(trailers.toSeq == Seq("trailers" -> "yea"))
        await(trailers.release())
      case frame =>
        fail(s"unexpected frame: $frame")
    }
  }

  test("client: response, with accumulation") {
    val id = 8
    var writeq = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      def close(d: Time) = ???
      def write(f: Http2Frame) = Future.Unit
    }
    val stats = new InMemoryStatsReceiver
    val stream = Netty4StreamTransport.client(id, writer, 2, stats)

    val rspf = stream.remote
    assert(!rspf.isDefined)

    stream.offerRemote(new DefaultHttp2HeadersFrame({
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
    stream.offerRemote(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.offerRemote(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.offerRemote(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.offerRemote({
      val hs = new DefaultHttp2Headers
      hs.set("trailers", "yea")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    })

    assert(dataf0.isDefined)
    await(dataf0) match {
      case data: Frame.Data =>
        assert(data.buf == buf)
        await(data.release())
      case frame =>
        fail(s"unexpected frame: $frame")
    }

    val dataf1 = data.read()
    assert(dataf1.isDefined)
    await(dataf1) match {
      case data: Frame.Data =>
        assert(data.buf == buf.concat(buf))
        await(data.release())
      case frame =>
        fail(s"unexpected frame: $frame")
    }

    val trailf = data.read()
    assert(trailf.isDefined)
    await(trailf) match {
      case trailers: Frame.Trailers =>
        assert(trailers.toSeq == Seq("trailers" -> "yea"))
        await(trailers.release())
      case frame =>
        fail(s"unexpected frame: $frame")
    }
  }

  test("server: provides a remote request") {
    val writer = new Netty4H2Writer {
      def close(d: Time) = ???
      def write(f: Http2Frame) = Future.Unit
    }
    val stream = Netty4StreamTransport.server(3, writer, Int.MaxValue)
    val reqf = stream.remote
    assert(!reqf.isDefined)

    stream.offerRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(3)
    })

    assert(reqf.isDefined)
    val req = await(reqf)
    assert(req.scheme == "h2")
    assert(req.method == Method("SUP"))
    assert(req.path == "/")
    assert(req.authority == "auf")
    val data = req.data match {
      case Stream.Nil => fail("empty stream")
      case d: Stream.Reader => d
    }

    val d0f = data.read()
    assert(!d0f.isDefined)
    stream.offerRemote(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(Buf.Utf8("data")), false).setStreamId(3))
    assert(d0f.isDefined)
    await(d0f) match {
      case f: Frame.Data =>
        assert(f.buf == Buf.Utf8("data"))
        assert(!f.isEnd)
        await(f.release())
      case f =>
        fail(s"unexpected frame: $f")
    }

    val d1f = data.read()
    assert(!d1f.isDefined)
    stream.offerRemote({
      val hs = new DefaultHttp2Headers
      hs.set("trailers", "chya")
      new DefaultHttp2HeadersFrame(hs, true)
    })
    eventually { assert(d1f.isDefined) }
    await(d1f) match {
      case f: Frame.Trailers =>
        assert(f.toSeq == Seq("trailers" -> "chya"))
        assert(f.isEnd)
        await(f.release())
      case f =>
        fail(s"unexpected frame: $f")
    }
  }

  test("server: writes a response on the underlying transport") {
    var writeq = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      def close(d: Time) = ???
      def write(f: Http2Frame) = {
        writeq = writeq :+ f
        Future.Unit
      }
    }
    val stream = Netty4StreamTransport.server(3, writer, Int.MaxValue)

    val rspdata = Stream()
    val rsp = {
      val hs = new DefaultHttp2Headers
      hs.status("202")
      Netty4Message.Response(hs, rspdata)
    }

    val wf = stream.write(rsp)
    assert(wf.isDefined)
    val endf = await(wf)
    assert(!endf.isDefined)

    assert(writeq.head == {
      val hs = new DefaultHttp2Headers
      hs.status("202")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(3)
    })
    writeq = writeq.tail

    val buf = Buf.Utf8("Looks like some tests failed.")
    val d0 = Frame.Data(buf, false, () => { log.debug("I AM RELEASED"); Future.Unit })
    await(rspdata.write(d0))
    assert(writeq.head == new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false).setStreamId(3))
    writeq = writeq.tail
    await(d0.onRelease)

    val d1 = Frame.Data(buf, false)
    await(rspdata.write(d1))
    assert(writeq.head == new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false).setStreamId(3))
    writeq = writeq.tail
    await(d1.onRelease)

    val trailers = new DefaultHttp2Headers
    trailers.set("trailin", "yams")
    await(rspdata.write(Netty4Message.Trailers(trailers)))
    assert(writeq.head == new DefaultHttp2HeadersFrame(trailers, true).setStreamId(3))
    writeq = writeq.tail
  }

}
