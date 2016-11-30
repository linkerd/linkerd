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

  trait ClientCtx {
    val id = 8

    val closed = new AtomicBoolean(false)
    def close(d: Time) =
      if (closed.compareAndSet(false, true)) Future.Unit
      else Future.exception(new Exception("double close"))

    var written = Queue.empty[Http2Frame]
    def write(f: Http2Frame) = synchronized {
      written = written :+ f
      Future.Unit
    }

    def mkWriter() = new Netty4H2Writer {
      override def localAddress = new SocketAddress { override def toString = "client" }
      override def remoteAddress = new SocketAddress { override def toString = "server" }
      def close(d: Time) = ClientCtx.this.close(d)
      def write(f: Http2Frame) = ClientCtx.this.write(f)
    }

    lazy val stats = new InMemoryStatsReceiver
    def mkStreamTransport(): Netty4StreamTransport[Request, Response] = {
      val tstats = new Netty4StreamTransport.StatsReceiver(stats)
      Netty4StreamTransport.client(id, mkWriter(), tstats)
    }
  }

  test("client: response") {
    val ctx = new ClientCtx {}
    import ctx._

    val st = mkStreamTransport()
    val rspf = st.onRemoteMessage
    assert(!rspf.isDefined)

    st.admitRemote(new DefaultHttp2HeadersFrame({
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
    st.admitRemote(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)).setStreamId(id))
    st.admitRemote({
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
    val ctx = new ClientCtx {}
    import ctx._

    val st = mkStreamTransport()
    val rspF = st.onRemoteMessage
    val reqStream = Stream.empty()
    await(st.write(Request("http", Method.Get, "host", "/path", reqStream)))
    assert(!st.onReset.isDefined)

    reqStream.reset(Reset.Cancel)

    eventually { assert(rspF.isDefined) }
    assert(await(rspF.liftToTry) == Throw(Reset.Cancel))

    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Local(Reset.Cancel)))
    assert(st.isClosed)

    assert(!closed.get)
  }

  test("client: upstream reset while request and response streams are open") {
    val ctx = new ClientCtx {}
    import ctx._

    val st = mkStreamTransport()
    val rspF = st.onRemoteMessage
    val reqStream = Stream()
    await(st.write(Request("http", Method.Get, "host", "/path", reqStream)))
    assert(!rspF.isDefined)

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.status("200")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(id)
    }))
    eventually { assert(rspF.isDefined) }
    val rsp = await(rspF)
    assert(rsp.status == Status.Ok)
    assert(rsp.stream.nonEmpty)
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)

    reqStream.reset(Reset.Cancel)

    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Local(Reset.Cancel)))
    assert(st.isClosed)

    assert(!closed.get)
  }

  test("client: upstream reset after response complete") {
    val ctx = new ClientCtx {}
    import ctx._

    val st = mkStreamTransport()
    val rspF = st.onRemoteMessage
    assert(!rspF.isDefined)
    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.status("200")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    }))
    assert(rspF.isDefined)
    val rsp = await(rspF)
    assert(rsp.status == Status.Ok)
    assert(rsp.stream.isEmpty)
    assert(!st.onReset.isDefined)
    assert(!st.isClosed)

    val reqStream = Stream()
    val streamF = await(st.write(Request("http", Method.Get, "host", "/path", reqStream)))
    await(reqStream.write(Frame.Data(Buf.Utf8("upshut"), eos = false)))
    assert(!streamF.isDefined)
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)

    reqStream.reset(Reset.InternalError)
    assert(await(streamF.liftToTry) == Throw(StreamError.Local(Reset.InternalError)))
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Local(Reset.InternalError)))
    assert(st.isClosed)
    assert(!closed.get)
  }

  test("client: downstream reset before response") {
    val ctx = new ClientCtx {}
    import ctx._

    val st = mkStreamTransport()
    val rspF = st.onRemoteMessage
    await(st.write(Request("http", Method.Get, "host", "/path", Stream.empty())))
    assert(!rspF.isDefined)
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)

    assert(st.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    assert(rspF.isDefined)
    assert(await(rspF.liftToTry) == Throw(Reset.Cancel))
    assert(st.isClosed)
    assert(st.onReset.isDefined)
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(!closed.get)
  }

  test("client: downstream reset while streaming response") {
    val ctx = new ClientCtx {}
    import ctx._

    val st = mkStreamTransport()
    val rspF = st.onRemoteMessage
    await(st.write(Request("http", Method.Get, "host", "/path", Stream.empty())))
    assert(!rspF.isDefined)

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.status("200")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(id)
    }))
    eventually { assert(rspF.isDefined) }
    val rsp = await(rspF)
    assert(rsp.status == Status.Ok)
    assert(rsp.stream.nonEmpty)
    assert(!st.onReset.isDefined)
    assert(!st.isClosed)

    val readF = rsp.stream.read()
    assert(!readF.isDefined)

    assert(st.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))

    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(st.isClosed)

    eventually { assert(readF.isDefined) }
    assert(await(readF.liftToTry) == Throw(Reset.Cancel))

    assert(!closed.get)
  }

  test("client: downstream reset while streaming request") {
    val ctx = new ClientCtx {}
    import ctx._

    val st = mkStreamTransport()
    val rspF = st.onRemoteMessage
    val reqStream = Stream()
    await(st.write(Request("http", Method.Get, "host", "/path", reqStream)))
    assert(!rspF.isDefined)

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.status("200")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    }))
    eventually { assert(rspF.isDefined) }
    val rsp = await(rspF)
    assert(rsp.status == Status.Ok)
    assert(rsp.stream.isEmpty)
    assert(!st.onReset.isDefined)
    assert(!st.isClosed)

    val readF = rsp.stream.read()
    assert(!readF.isDefined)

    assert(st.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))

    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(st.isClosed)

    eventually { assert(readF.isDefined) }
    assert(await(readF.liftToTry) == Throw(Reset.Cancel))

    assert(!closed.get)
  }

  trait ServerCtx {
    val id = 7

    val closed = new AtomicBoolean(false)
    def close(d: Time) =
      if (closed.compareAndSet(false, true)) Future.Unit
      else Future.exception(new Exception("double close"))

    var written = Queue.empty[Http2Frame]
    def write(f: Http2Frame) = synchronized {
      written = written :+ f
      Future.Unit
    }

    def mkWriter() = new Netty4H2Writer {
      override def localAddress = new SocketAddress { override def toString = "server" }
      override def remoteAddress = new SocketAddress { override def toString = "client" }
      def close(d: Time) = ServerCtx.this.close(d)
      def write(f: Http2Frame) = ServerCtx.this.write(f)
    }

    lazy val stats = new InMemoryStatsReceiver
    def mkStreamTransport(): Netty4StreamTransport[Response, Request] = {
      val tstats = new Netty4StreamTransport.StatsReceiver(stats)
      Netty4StreamTransport.server(id, mkWriter(), tstats)
    }
  }

  test("server: provides a remote request") {
    val ctx = new ServerCtx {}
    import ctx._

    val st = mkStreamTransport()
    val reqf = st.onRemoteMessage
    assert(!reqf.isDefined)

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(id)
    }))

    assert(reqf.isDefined)
    val req = await(reqf)
    assert(req.scheme == "h2")
    assert(req.method == Method("SUP"))
    assert(req.path == "/")
    assert(req.authority == "auf")

    val d0f = req.stream.read()
    assert(!d0f.isDefined)
    assert(st.admitRemote({
      val bb = BufAsByteBuf.Owned(Buf.Utf8("data"))
      new DefaultHttp2DataFrame(bb, false).setStreamId(id)
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
    assert(st.admitRemote({
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
    val ctx = new ServerCtx {}
    import ctx._

    val st = mkStreamTransport()

    val rspStreamQ = new AsyncQueue[Frame]
    val rspStream = Stream(rspStreamQ)
    val rsp = {
      val hs = new DefaultHttp2Headers
      hs.status("202")
      Netty4Message.Response(hs, rspStream)
    }

    val endF = await(st.write(rsp))
    assert(!endF.isDefined)

    ctx.synchronized {
      assert(written.head == {
        val hs = new DefaultHttp2Headers
        hs.status("202")
        new DefaultHttp2HeadersFrame(hs, false).setStreamId(id)
      })
      written = written.tail
    }

    val buf = Buf.Utf8("Looks like some tests were totally excellent")
    assert(rspStreamQ.offer(Frame.Data(buf, false)))
    ctx.synchronized {
      assert(written.head == new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false).setStreamId(id))
      written = written.tail
    }

    assert(rspStreamQ.offer(Frame.Data(buf, false)))
    ctx.synchronized {
      assert(written.head == new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false).setStreamId(id))
      written = written.tail
    }

    val trailers = new DefaultHttp2Headers
    trailers.set("trailin", "yams")
    assert(rspStreamQ.offer(Netty4Message.Trailers(trailers)))
    ctx.synchronized {
      assert(written.head == new DefaultHttp2HeadersFrame(trailers, true).setStreamId(id))
      written = written.tail
    }
  }

  test("server: downstream reset while request and response streams are open") {
    val ctx = new ServerCtx {}
    import ctx._

    val st = mkStreamTransport()
    val reqF = st.onRemoteMessage
    assert(!reqF.isDefined)

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(id)
    }))
    eventually { assert(reqF.isDefined) }

    val rspStream = Stream()
    val rspEndF = await(st.write(Response(Status.Ok, rspStream)))
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)
    assert(!rspEndF.isDefined)

    rspStream.reset(Reset.Cancel)
    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Local(Reset.Cancel)))
    assert(st.isClosed)

    assert(!closed.get)
  }

  test("server: downstream reset while streaming response") {
    val ctx = new ServerCtx {}
    import ctx._

    val st = mkStreamTransport()
    val reqF = st.onRemoteMessage
    assert(!reqF.isDefined)

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(id)
    }))
    eventually { assert(reqF.isDefined) }

    val rspStream = Stream.empty()
    await(st.write(Response(Status.Ok, rspStream)))
    ctx.synchronized {
      assert(written.length == 1)
      assert(written.last.isInstanceOf[Http2HeadersFrame])
    }
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)

    rspStream.reset(Reset.Refused)
    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Local(Reset.Refused)))
    assert(st.isClosed)

    assert(!closed.get)
  }

  test("server: downstream reset while streaming request") {
    val ctx = new ServerCtx {}
    import ctx._

    val st = mkStreamTransport()
    val reqF = st.onRemoteMessage
    assert(!reqF.isDefined)

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    }))
    eventually { assert(reqF.isDefined) }

    val rspStream = Stream()
    await(st.write(Response(Status.Ok, rspStream)))
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)

    rspStream.reset(Reset.InternalError)
    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Local(Reset.InternalError)))
    assert(st.isClosed)

    assert(!closed.get)
  }

  test("server: upstream reset before response") {
    val ctx = new ServerCtx {}
    import ctx._

    val st = mkStreamTransport()
    val reqF = st.onRemoteMessage

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    }))
    eventually { assert(reqF.isDefined) }

    val rspStream = Stream()
    await(st.write(Response(Status.Ok, rspStream)))
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)

    assert(st.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(st.isClosed)

    assert(!closed.get)
  }

  test("server: upstream reset while streaming response") {
    val ctx = new ServerCtx {}
    import ctx._

    val st = mkStreamTransport()
    val reqF = st.onRemoteMessage

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, true).setStreamId(id)
    }))
    eventually { assert(reqF.isDefined) }

    val rspStream = Stream()
    await(st.write(Response(Status.Ok, rspStream)))
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)

    assert(st.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(st.isClosed)

    assert(!closed.get)
  }

  test("server: upstream reset while streaming request") {
    val ctx = new ServerCtx {}
    import ctx._

    val st = mkStreamTransport()
    val reqF = st.onRemoteMessage

    assert(st.admitRemote({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(id)
    }))
    eventually { assert(reqF.isDefined) }

    await(st.write(Response(Status.Ok, Stream.empty())))
    assert(!st.isClosed)
    assert(!st.onReset.isDefined)

    assert(st.admitRemote(new DefaultHttp2ResetFrame(Http2Error.CANCEL).setStreamId(id)))
    eventually { assert(st.onReset.isDefined) }
    assert(await(st.onReset.liftToTry) == Throw(StreamError.Remote(Reset.Cancel)))
    assert(st.isClosed)

    assert(!closed.get)
  }

}
