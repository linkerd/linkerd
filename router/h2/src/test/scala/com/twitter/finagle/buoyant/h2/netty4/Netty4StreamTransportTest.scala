package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Failure
import com.twitter.finagle.netty4.BufAsByteBuf
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Time, Throw}
import io.buoyant.test.FunSuite
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http2._
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.Queue

class Netty4StreamTransportTest extends FunSuite {
  setLogLevel(com.twitter.logging.Level.OFF)

  test("client: response") {
    val id = 8
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      def close(d: Time) = ???
      def write(f: Http2Frame) = ???
    }
    val stats = new InMemoryStatsReceiver
    val tstats = new Netty4StreamTransport.StatsReceiver(stats)
    val stream = Netty4StreamTransport.client(id, writer, tstats)

    val rspf = stream.remoteMsg
    assert(!rspf.isDefined)

    stream.admitRemote(new DefaultHttp2HeadersFrame({
      val hs = new DefaultHttp2Headers
      hs.status("222")
      hs
    }))

    assert(rspf.isDefined)
    val rsp = await(rspf)
    val endf = rsp.stream.onEnd
    assert(!endf.isDefined)
    assert(rsp.status == Status.Cowabunga)
    assert(rsp.stream.nonEmpty)

    val dataf = rsp.stream.read()
    assert(!dataf.isDefined)

    val buf = Buf.Utf8("space ghost coast to coast")
    stream.admitRemote(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    stream.admitRemote({
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

    val trailf = rsp.stream.read()
    assert(trailf.isDefined)
    await(trailf) match {
      case trailers: Frame.Trailers =>
        assert(trailers.toSeq == Seq("trailers" -> "yea"))
      case frame =>
        fail(s"unexpected frame: $frame")
    }
  }

  test("client: upstream reset while waiting on downstream response") {
    val id = 8
    val closed = new AtomicBoolean(false)
    var written = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      override def close(d: Time) =
        if (closed.compareAndSet(false, true)) Future.Unit
        else Future.exception(new Exception("double close"))

      override def write(f: Http2Frame) = synchronized {
        written = written :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val tstats = new Netty4StreamTransport.StatsReceiver(stats)
    val stream = Netty4StreamTransport.client(id, writer, tstats)

    val rspF = stream.remoteMsg

    assert(synchronized(written).isEmpty)
    assert(!rspF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    await(stream.write(Request("http", Method.Get, "host", "/path", Stream.empty())))
    assert(synchronized(written).length == 1)
    assert(synchronized(written).last.isInstanceOf[Http2HeadersFrame])
    assert(!rspF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    assert(rspF.isDefined)
    assert(await(rspF.liftToTry) == Throw(Reset.Cancel))
    assert(stream.isClosed)
    assert(stream.onReset.isDefined)
    assert(await(stream.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(synchronized(written).length == 1)
    assert(!closed.get)
  }

  test("client: upstream reset while request and response streams are open") {
    val id = 8
    val closed = new AtomicBoolean(false)
    var written = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      override def close(d: Time) =
        if (closed.compareAndSet(false, true)) Future.Unit
        else Future.exception(new Exception("double close"))

      override def write(f: Http2Frame) = synchronized {
        written = written :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val tstats = new Netty4StreamTransport.StatsReceiver(stats)
    val stream = Netty4StreamTransport.client(id, writer, tstats)

    val rspF = stream.remoteMsg

    assert(synchronized(written).isEmpty)
    assert(!rspF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    val localStream = Stream()
    await(stream.write(Request("http", Method.Get, "host", "/path", localStream)))
    assert(synchronized(written).length == 1)
    assert(synchronized(written).last.isInstanceOf[Http2HeadersFrame])
    assert(!rspF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.status("200")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    }))
    assert(synchronized(written).length == 1)
    assert(rspF.isDefined)
    val rsp = await(rspF)
    assert(rsp.status == Status.Ok)
    assert(rsp.stream.isEmpty)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    assert(stream.isClosed)
    assert(stream.onReset.isDefined)
    assert(await(stream.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(synchronized(written).length == 1)
    assert(!closed.get)
  }

  test("client: upstream reset after response complete") {
    val id = 8
    val closed = new AtomicBoolean(false)
    var written = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress { override def toString = "client" }
      override def remoteAddress = new SocketAddress { override def toString = "server" }
      override def close(d: Time) =
        if (closed.compareAndSet(false, true)) Future.Unit
        else Future.exception(new Exception("double close"))

      override def write(f: Http2Frame) = synchronized {
        written = written :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val tstats = new Netty4StreamTransport.StatsReceiver(stats)
    val stream = Netty4StreamTransport.client(id, writer, tstats)

    val rspF = stream.remoteMsg
    assert(synchronized(written).isEmpty)
    assert(!rspF.isDefined)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.status("200")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    }))
    assert(rspF.isDefined)
    val rsp = await(rspF)
    assert(rsp.status == Status.Ok)
    assert(rsp.stream.isEmpty)

    val reqStream = Stream()
    val streamF = await(stream.write(Request("http", Method.Get, "host", "/path", reqStream)))

    assert(synchronized(written).length == 1)
    assert(synchronized(written).last.isInstanceOf[Http2HeadersFrame])
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    await(reqStream.write(Frame.Data(Buf.Utf8("upshut"), eos = false)))
    assert(synchronized(written).length == 2)
    assert(synchronized(written).last.isInstanceOf[Http2DataFrame])
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(!streamF.isDefined)
    log.debug("resetting request stream")
    reqStream.reset(Reset.InternalError)
    assert(synchronized(written).length == 2)
    assert(await(streamF.liftToTry) == Throw(StreamError.Local(Reset.InternalError)))

    assert(await(stream.onReset.liftToTry) == Throw(StreamError.Local(Reset.InternalError)))
    assert(stream.isClosed)
    assert(!closed.get)
  }

  test("client: canceled response causes downstream reset") {
    val id = 8
    val closed = new AtomicBoolean(false)
    var written = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      override def close(d: Time) =
        if (closed.compareAndSet(false, true)) Future.Unit
        else Future.exception(new Exception("double close"))

      override def write(f: Http2Frame) = synchronized {
        written = written :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val tstats = new Netty4StreamTransport.StatsReceiver(stats)
    val stream = Netty4StreamTransport.client(id, writer, tstats)

    val rspF = stream.remoteMsg
    assert(synchronized(written).isEmpty)
    assert(!rspF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    val streamF = await(stream.write(Request("http", Method.Get, "host", "/path", Stream.empty())))
    assert(synchronized(written).length == 1)
    assert(synchronized(written).last.isInstanceOf[Http2HeadersFrame])
    assert(!rspF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(!streamF.isDefined)
    rspF.raise(Failure("shut up").flagged(Failure.Interrupted))
    assert(synchronized(written).length == 1)
    assert(rspF.isDefined)
    assert(await(rspF.liftToTry) == Throw(Reset.Cancel))
    assert(stream.isClosed)
    assert(stream.onReset.isDefined)
    assert(await(stream.onReset.liftToTry) == Throw(StreamError.Local(Reset.Cancel)))
    assert(!closed.get)
  }

  test("server: provides a remote request") {
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      def close(d: Time) = ???
      def write(f: Http2Frame) = ???
    }
    val stream = Netty4StreamTransport.server(3, writer)
    val reqf = stream.remoteMsg
    assert(!reqf.isDefined)

    assert(stream.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(3)
    }))

    assert(reqf.isDefined)
    val req = await(reqf)
    assert(req.scheme == "h2")
    assert(req.method == Method("SUP"))
    assert(req.path == "/")
    assert(req.authority == "auf")

    val d0f = req.stream.read()
    assert(!d0f.isDefined)
    assert(stream.admitRemote({
      new DefaultHttp2DataFrame(BufAsByteBuf.Owned(Buf.Utf8("data")), false).setStreamId(3)
    }))
    assert(d0f.isDefined)
    await(d0f) match {
      case f: Frame.Data =>
        assert(f.buf == Buf.Utf8("data"))
        assert(!f.isEnd)
      case f =>
        fail(s"unexpected frame: $f")
    }

    val d1f = req.stream.read()
    assert(!d1f.isDefined)
    assert(stream.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.set("trailers", "chya")
      new DefaultHttp2HeadersFrame(hs, true)
    }))
    assert(d1f.isDefined)
    await(d1f) match {
      case f: Frame.Trailers =>
        assert(f.toSeq == Seq("trailers" -> "chya"))
        assert(f.isEnd)
      case f =>
        fail(s"unexpected frame: $f")
    }
  }

  test("server: writes a response on the underlying transport") {
    var writeq = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      def close(d: Time) = ???
      def write(f: Http2Frame) = {
        writeq = writeq :+ f
        Future.Unit
      }
    }
    val stream = Netty4StreamTransport.server(3, writer)

    val rspdataQ = new AsyncQueue[Frame]
    val rsp = {
      val hs = new DefaultHttp2Headers
      hs.status("202")
      Netty4Message.Response(hs, Stream(rspdataQ))
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

    val buf = Buf.Utf8("Looks like some tests were totally excellent")
    assert(rspdataQ.offer(Frame.Data(buf, false)))
    assert(writeq.head == new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false).setStreamId(3))
    writeq = writeq.tail

    assert(rspdataQ.offer(Frame.Data(buf, false)))
    assert(writeq.head == new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false).setStreamId(3))
    writeq = writeq.tail

    val trailers = new DefaultHttp2Headers
    trailers.set("trailin", "yams")
    assert(rspdataQ.offer(Netty4Message.Trailers(trailers)))
    assert(writeq.head == new DefaultHttp2HeadersFrame(trailers, true).setStreamId(3))
    writeq = writeq.tail
  }

  test("client: downstream reset while request and response streams are open") {
    val id = 6
    val closed = new AtomicBoolean(false)
    var written = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      override def close(d: Time) =
        if (closed.compareAndSet(false, true)) Future.Unit
        else Future.exception(new Exception("double close"))

      override def write(f: Http2Frame) = synchronized {
        written = written :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val tstats = new Netty4StreamTransport.StatsReceiver(stats)
    val stream = Netty4StreamTransport.server(id, writer, tstats)

    val reqF = stream.remoteMsg

    assert(synchronized(written).isEmpty)
    assert(!reqF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(3)
    }))
    assert(reqF.isDefined)
    assert(synchronized(written).isEmpty)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    assert(synchronized(written).isEmpty)
    assert(stream.isClosed)
    assert(stream.onReset.isDefined)
    assert(await(stream.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(!closed.get)
  }

  test("server: downstream reset while streaming response") {
    val id = 6
    val closed = new AtomicBoolean(false)
    var written = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      override def close(d: Time) =
        if (closed.compareAndSet(false, true)) Future.Unit
        else Future.exception(new Exception("double close"))

      override def write(f: Http2Frame) = synchronized {
        written = written :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val tstats = new Netty4StreamTransport.StatsReceiver(stats)
    val stream = Netty4StreamTransport.server(id, writer, tstats)

    val reqF = stream.remoteMsg

    assert(synchronized(written).isEmpty)
    assert(!reqF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(3)
    }))
    assert(reqF.isDefined)
    assert(synchronized(written).isEmpty)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    await(stream.write(Response(Status.Ok, Stream.empty())))
    assert(synchronized(written).length == 1)
    assert(synchronized(written).last.isInstanceOf[Http2HeadersFrame])
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    assert(synchronized(written).length == 1)
    assert(stream.isClosed)
    assert(stream.onReset.isDefined)
    assert(await(stream.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(!closed.get)
  }

  test("server: downstream reset while streaming request") {
    val id = 6
    val closed = new AtomicBoolean(false)
    var written = Queue.empty[Http2Frame]
    val writer = new Netty4H2Writer {
      override def localAddress = new SocketAddress {}
      override def remoteAddress = new SocketAddress {}
      override def close(d: Time) =
        if (closed.compareAndSet(false, true)) Future.Unit
        else Future.exception(new Exception("double close"))

      override def write(f: Http2Frame) = synchronized {
        written = written :+ f
        Future.Unit
      }
    }
    val stats = new InMemoryStatsReceiver
    val tstats = new Netty4StreamTransport.StatsReceiver(stats)
    val stream = Netty4StreamTransport.server(id, writer, tstats)

    val reqF = stream.remoteMsg

    assert(synchronized(written).isEmpty)
    assert(!reqF.isDefined)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(3)
    }))
    assert(reqF.isDefined)
    assert(synchronized(written).isEmpty)
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    val rspStream = Stream()
    await(stream.write(Response(Status.Ok, rspStream)))
    assert(synchronized(written).length == 1)
    assert(synchronized(written).last.isInstanceOf[Http2HeadersFrame])
    assert(!stream.isClosed)
    assert(!stream.onReset.isDefined)
    assert(!closed.get)

    assert(stream.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    assert(synchronized(written).length == 1)
    assert(stream.isClosed)
    assert(stream.onReset.isDefined)
    assert(await(stream.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(!closed.get)
  }
}
