package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Failure
import com.twitter.finagle.netty4.BufAsByteBuf
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.FunSuite
import io.netty.handler.codec.http2._
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.Queue

class Netty4StreamTransportTest extends FunSuite {
  setLogLevel(com.twitter.logging.Level.OFF)

  trait Ctx {
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
      override def localAddress = new SocketAddress { override def toString = "local" }
      override def remoteAddress = new SocketAddress { override def toString = "remote" }
      def close(d: Time) = Ctx.this.close(d)
      def write(f: Http2Frame) = Ctx.this.write(f)
    }

    val stats = new InMemoryStatsReceiver
  }

  trait ClientCtx extends Ctx {
    def mkStreamTransport(): Netty4StreamTransport[Request, Response] = {
      val tstats = new Netty4StreamTransport.StatsReceiver(stats)
      Netty4StreamTransport.client(id, mkWriter(), tstats)
    }

    val transport = mkStreamTransport()
    val rspF = transport.onRecvMessage

    @volatile var receivedMsg = false

    def assertRecvResponse(status: Int, eos: Boolean): Response = {
      assert(transport.recv({
        val hs = new DefaultHttp2Headers
        hs.status(status.toString)
        new DefaultHttp2HeadersFrame(hs, eos).streamId(id)
      }))
      eventually { assert(rspF.isDefined) }
      receivedMsg = true
      val rsp = await(rspF)
      assert(rsp.status == Status.fromCode(status))
      assert(rsp.stream.isEmpty == eos)
      rsp
    }

    def assertRemoteReset(rst: Reset): Unit = {
      val err = Netty4Message.Reset.toHttp2Error(rst)
      val f = new DefaultHttp2ResetFrame(err).streamId(id)
      assert(transport.recv(f))
      eventually { assert(transport.isClosed) }

      if (!receivedMsg) {
        assert(await(rspF.liftToTry) == Throw(rst))
      }

      eventually { assert(transport.onReset.isDefined) }
      assert(await(transport.onReset.liftToTry) == Throw(StreamError.Remote(rst)))
      ()
    }

  }

  trait ServerCtx extends Ctx {
    def mkStreamTransport(): Netty4StreamTransport[Response, Request] = {
      val tstats = new Netty4StreamTransport.StatsReceiver(stats)
      Netty4StreamTransport.server(id, mkWriter(), tstats)
    }

    val transport = mkStreamTransport()
    val reqF = transport.onRecvMessage

    @volatile var receivedMsg = false

    def assertRecvRequest(eos: Boolean): Request = {
      assert(transport.recv({
        val hs = new DefaultHttp2Headers
        hs.scheme("testscheme")
        hs.method("TEST")
        hs.path("/")
        hs.authority("auf")
        new DefaultHttp2HeadersFrame(hs, eos).streamId(id)
      }))
      eventually { assert(reqF.isDefined) }
      receivedMsg = true
      val req = await(reqF)
      assert(req.scheme == "testscheme")
      assert(req.method == Method("TEST"))
      assert(req.path == "/")
      assert(req.authority == "auf")
      req
    }

    def assertRemoteReset(rst: Reset): Unit = {
      val err = Netty4Message.Reset.toHttp2Error(rst)
      val f = new DefaultHttp2ResetFrame(err).streamId(id)
      assert(transport.recv(f))
      if (!receivedMsg) {
        assert(await(reqF.liftToTry) == Throw(rst))
      }
      assert(await(transport.onReset.liftToTry) == Throw(StreamError.Remote(rst)))
      assert(transport.isClosed)
      ()
    }
  }

  test("client: response") {
    val ctx = new ClientCtx {}
    import ctx._

    assert(!rspF.isDefined)

    transport.recv(new DefaultHttp2HeadersFrame({
      val hs = new DefaultHttp2Headers
      hs.status("222")
      hs
    }))

    assert(rspF.isDefined)
    val rsp = await(rspF)
    val endF = rsp.stream.onEnd
    assert(!endF.isDefined)
    assert(rsp.stream.nonEmpty)

    val dataf = rsp.stream.read()
    assert(!dataf.isDefined)

    val buf = Buf.Utf8("space ghost coast to coast")
    transport.recv(new DefaultHttp2DataFrame(BufAsByteBuf(buf)).streamId(id))
    transport.recv({
      val hs = new DefaultHttp2Headers
      hs.set("trailers", "yea")
      new DefaultHttp2HeadersFrame(hs, true).streamId(id)
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

  test("client: full request/response with empty streams") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream.empty()
    val req = Request("http", Method.Get, "host", "/path", reqStream)
    val endF = await(transport.send(req))
    assert(!rspF.isDefined)

    val rsp = assertRecvResponse(200, eos = true)
    reqStream.close()

    eventually { assert(transport.onReset.isDefined) }
    assert(await(transport.onReset.liftToTry) == Return.Unit)
    assert(transport.isClosed)
    assert(!closed.get)
  }

  test("client: open: local reset") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream()
    await(transport.send(Request("http", Method.Get, "host", "/path", reqStream)))
    assert(!rspF.isDefined)

    val rsp = assertRecvResponse(200, eos = false)
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)

    reqStream.reset(Reset.Cancel)
    eventually { assert(transport.onReset.isDefined) }
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(Reset.Cancel)))
    assert(transport.isClosed)
    assert(!closed.get)
  }

  test("client: open: remote reset") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream()
    await(transport.send(Request("http", Method.Get, "host", "/path", reqStream)))
    assert(!rspF.isDefined)

    val rsp = assertRecvResponse(200, eos = false)
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)

    assertRemoteReset(Reset.Cancel)
    assert(!closed.get)
  }

  test("client: open before remote response: local reset") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream()
    await(transport.send(Request("http", Method.Get, "host", "/path", reqStream)))
    assert(!transport.onReset.isDefined)

    reqStream.reset(Reset.Cancel)
    eventually { assert(rspF.isDefined) }
    assert(await(rspF.liftToTry) == Throw(Reset.Cancel))

    eventually { assert(transport.onReset.isDefined) }
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(Reset.Cancel)))
    assert(transport.isClosed)

    assert(!closed.get)
  }

  test("client: open before remote response: remote reset") {
    val ctx = new ClientCtx {}
    import ctx._

    val localStream = Stream()
    await(transport.send(Request("http", Method.Get, "host", "/path", localStream)))
    assert(!rspF.isDefined)
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)

    assertRemoteReset(Reset.Cancel)
    assert(!closed.get)
  }

  test("client: local half closed: local reset") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream.empty()
    await(transport.send(Request("http", Method.Get, "host", "/path", reqStream)))
    assert(!rspF.isDefined)

    val rsp = assertRecvResponse(200, eos = false)
    assert(!transport.onReset.isDefined)
    assert(!transport.isClosed)

    val readF = rsp.stream.read()
    assert(!readF.isDefined)

    reqStream.reset(Reset.Cancel)
    eventually { assert(readF.isDefined) }
    assert(await(readF.liftToTry) == Throw(Reset.Cancel))
    assert(!closed.get)
  }

  test("client: local half closed: remote reset") {
    val ctx = new ClientCtx {}
    import ctx._

    await(transport.send(Request("http", Method.Get, "host", "/path", Stream.empty())))
    assert(!rspF.isDefined)

    val rsp = assertRecvResponse(200, eos = false)
    assert(!transport.onReset.isDefined)
    assert(!transport.isClosed)

    val readF = rsp.stream.read()
    assert(!readF.isDefined)

    assertRemoteReset(Reset.Cancel)
    eventually { assert(readF.isDefined) }
    assert(await(readF.liftToTry) == Throw(Reset.Cancel))
    assert(!closed.get)
  }

  test("client: remote half closed: local reset") {
    val ctx = new ClientCtx {}
    import ctx._

    assert(!rspF.isDefined)
    val reqStream = Stream()
    val endF = await(transport.send(Request("http", Method.Get, "host", "/path", reqStream)))
    await(reqStream.write(Frame.Data(Buf.Utf8("upshut"), eos = false)))
    assert(!endF.isDefined)

    val rsp = assertRecvResponse(200, eos = true)
    assert(!transport.onReset.isDefined)
    assert(!transport.isClosed)

    reqStream.reset(Reset.InternalError)
    assert(await(endF.liftToTry) == Throw(StreamError.Local(Reset.InternalError)))
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(Reset.InternalError)))
    assert(transport.isClosed)
    assert(!closed.get)
  }

  test("client: remote half closed: remote reset") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream()
    await(transport.send(Request("http", Method.Get, "host", "/path", reqStream)))
    assert(!rspF.isDefined)

    val rsp = assertRecvResponse(200, eos = false)
    assert(!transport.onReset.isDefined)
    assert(!transport.isClosed)

    val readF = rsp.stream.read()
    assert(!readF.isDefined)

    assertRemoteReset(Reset.Cancel)
    eventually { assert(readF.isDefined) }
    assert(await(readF.liftToTry) == Throw(Reset.Cancel))
    assert(!closed.get)
  }

  test("client: response cancelation resets stream: Reset") {
    val ctx = new ClientCtx {}
    assert(!ctx.rspF.isDefined)
    ctx.rspF.raise(Reset.EnhanceYourCalm)
    assert(await(ctx.rspF.liftToTry) == Throw(Reset.EnhanceYourCalm))
  }

  test("client: response cancelation resets stream: Failure(Reset)") {
    val ctx = new ClientCtx {}
    assert(!ctx.rspF.isDefined)
    ctx.rspF.raise(Failure(Reset.EnhanceYourCalm))
    assert(await(ctx.rspF.liftToTry) == Throw(Reset.EnhanceYourCalm))
  }

  test("client: response cancelation resets stream: Failure(Interrupted)") {
    val ctx = new ClientCtx {}
    assert(!ctx.rspF.isDefined)
    ctx.rspF.raise(Failure("foo").flagged(Failure.Interrupted))
    assert(await(ctx.rspF.liftToTry) == Throw(Reset.Cancel))
  }

  test("client: response cancelation resets stream: Failure(Rejected)") {
    val ctx = new ClientCtx {}
    assert(!ctx.rspF.isDefined)
    ctx.rspF.raise(Failure("foo").flagged(Failure.Rejected))
    assert(await(ctx.rspF.liftToTry) == Throw(Reset.Refused))
  }

  test("client: response cancelation resets stream: Exception") {
    val ctx = new ClientCtx {}
    assert(!ctx.rspF.isDefined)
    ctx.rspF.raise(new Exception("ugh"))
    assert(await(ctx.rspF.liftToTry) == Throw(Reset.InternalError))
  }

  test("client: fails request with `connection` header") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream.empty()
    val req = Request("http", Method.Get, "host", "/path", reqStream)
    req.headers.set("connection", "awesome")
    val rst = Reset.ProtocolError
    val localRst = StreamError.Local(rst)
    assert(await(transport.send(req).liftToTry) == Throw(localRst))
    assert(await(rspF.liftToTry) == Throw(rst))
    assert(await(transport.onReset.liftToTry) == Throw(localRst))
    assert(transport.isClosed)
  }

  test("client: fails request with `te: chunked, trailers`") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream.empty()
    val req = Request("http", Method.Get, "host", "/path", reqStream)
    req.headers.set("te", "chunked, trailers")
    val rst = Reset.ProtocolError
    val localRst = StreamError.Local(rst)
    assert(await(transport.send(req).liftToTry) == Throw(localRst))
    assert(await(rspF.liftToTry) == Throw(rst))
    assert(await(transport.onReset.liftToTry) == Throw(localRst))
    assert(transport.isClosed)
  }

  test("client: allows request with `te: trailers`") {
    val ctx = new ClientCtx {}
    import ctx._

    val reqStream = Stream.empty()
    val req = Request("http", Method.Get, "host", "/path", reqStream)
    req.headers.set("te", "trailers")
    val rst = Reset.ProtocolError
    val localRst = StreamError.Local(rst)
    val endF = await(transport.send(req))
    assert(!rspF.isDefined)
    assert(!transport.onReset.isDefined)
    assert(!transport.isClosed)

    assertRecvResponse(200, eos = true)
    assert(rspF.isDefined)
    reqStream.close()
    assert(transport.onReset.poll == Some(Return.Unit))
    assert(transport.isClosed)
  }

  test("server: provides a remote request") {
    val ctx = new ServerCtx {}
    import ctx._

    assert(!reqF.isDefined)

    val req = assertRecvRequest(eos = false)
    val d0f = req.stream.read()
    assert(!d0f.isDefined)
    assert(transport.recv({
      val bb = BufAsByteBuf(Buf.Utf8("data"))
      new DefaultHttp2DataFrame(bb, false).streamId(id)
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
    assert(transport.recv({
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

    val rspStreamQ = new AsyncQueue[Frame]
    val rspStream = Stream(rspStreamQ)
    val rsp = {
      val hs = new DefaultHttp2Headers
      hs.status("202")
      Netty4Message.Response(hs, rspStream)
    }

    val endF = await(transport.send(rsp))
    assert(!endF.isDefined)

    ctx.synchronized {
      assert(written.head == {
        val hs = new DefaultHttp2Headers
        hs.status("202")
        new DefaultHttp2HeadersFrame(hs, false).streamId(id)
      })
      written = written.tail
    }

    val buf = Buf.Utf8("Looks like some tests were totally excellent")
    assert(rspStreamQ.offer(Frame.Data(buf, false)))
    ctx.synchronized {
      assert(written.head == new DefaultHttp2DataFrame(BufAsByteBuf(buf), false).streamId(id))
      written = written.tail
    }

    assert(rspStreamQ.offer(Frame.Data(buf, false)))
    ctx.synchronized {
      assert(written.head == new DefaultHttp2DataFrame(BufAsByteBuf(buf), false).streamId(id))
      written = written.tail
    }

    val trailers = new DefaultHttp2Headers
    trailers.set("trailin", "yams")
    assert(rspStreamQ.offer(Netty4Message.Trailers(trailers)))
    ctx.synchronized {
      assert(written.head == new DefaultHttp2HeadersFrame(trailers, true).streamId(id))
      written = written.tail
    }
  }

  test("server: open: local reset") {
    val ctx = new ServerCtx {}
    import ctx._

    val req = assertRecvRequest(eos = false)

    val rspStream = Stream()
    val rspEndF = await(transport.send(Response(Status.Ok, rspStream)))
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)
    assert(!rspEndF.isDefined)

    rspStream.reset(Reset.Cancel)
    eventually { assert(transport.onReset.isDefined) }
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(Reset.Cancel)))
    assert(transport.isClosed)
    assert(!closed.get)
  }

  test("server: open: remote reset") {
    val ctx = new ServerCtx {}
    import ctx._

    val req = assertRecvRequest(eos = false)

    val rspStream = Stream()
    val rspEndF = await(transport.send(Response(Status.Ok, rspStream)))
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)
    assert(!rspEndF.isDefined)

    assertRemoteReset(Reset.Cancel)
    assert(!closed.get)
  }

  test("server: open before local response: local reset") {
    val ctx = new ServerCtx {}
    import ctx._

    val req = assertRecvRequest(eos = false)

    val rspStream = Stream()
    await(transport.send(Response(Status.Ok, rspStream)))
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)

    rspStream.reset(Reset.Cancel)
    eventually { assert(transport.onReset.isDefined) }
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(Reset.Cancel)))
    assert(transport.isClosed)
    assert(!closed.get)
  }

  test("server: open before local response: remote reset") {
    val ctx = new ServerCtx {}
    import ctx._

    val req = assertRecvRequest(eos = false)

    val rspStream = Stream()
    await(transport.send(Response(Status.Ok, rspStream)))
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)
    assertRemoteReset(Reset.Cancel)
    assert(!closed.get)
  }
  test("server: remote half closed: local reset") {
    val ctx = new ServerCtx {}
    import ctx._

    val req = assertRecvRequest(eos = false)

    val rspStream = Stream.empty()
    await(transport.send(Response(Status.Ok, rspStream)))
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)

    rspStream.reset(Reset.Refused)
    eventually { assert(transport.onReset.isDefined) }
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(Reset.Refused)))
    assert(transport.isClosed)
    assert(!closed.get)
  }

  test("server: remote half closed: remote reset") {
    val ctx = new ServerCtx {}
    import ctx._

    val req = assertRecvRequest(eos = true)

    val rspStream = Stream.empty()
    await(transport.send(Response(Status.Ok, rspStream)))
    ctx.synchronized {
      assert(written.length == 1)
      assert(written.last.isInstanceOf[Http2HeadersFrame])
    }
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)

    assertRemoteReset(Reset.Cancel)
    assert(!closed.get)
  }

  test("server: local half closed: local reset") {
    val ctx = new ServerCtx {}
    import ctx._

    val req = assertRecvRequest(eos = false)
    val rspStream = Stream.empty()
    await(transport.send(Response(Status.Ok, rspStream)))
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)

    rspStream.reset(Reset.Refused)
    eventually { assert(transport.onReset.isDefined) }
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(Reset.Refused)))
    assert(transport.isClosed)
    assert(!closed.get)
  }

  test("server: local half closed: remote reset") {
    val ctx = new ServerCtx {}
    import ctx._

    val req = assertRecvRequest(eos = false)
    await(transport.send(Response(Status.Ok, Stream.empty())))
    assert(!transport.isClosed)
    assert(!transport.onReset.isDefined)
    assertRemoteReset(Reset.Cancel)
    assert(!closed.get)
  }

  test("server: fails request with `connection` header") {
    val ctx = new ServerCtx {}
    import ctx._

    assert(transport.recv({
      val hs = new DefaultHttp2Headers
      hs.scheme("testscheme")
      hs.method("TEST")
      hs.path("/")
      hs.authority("auf")
      hs.set("connection", "blah")
      new DefaultHttp2HeadersFrame(hs, true).streamId(id)
    }))

    val rst = Reset.ProtocolError
    assert(await(reqF.liftToTry) == Throw(rst))
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(rst)))
    assert(transport.isClosed)
  }

  test("server: fails request with `te: chunked, trailers`") {
    val ctx = new ServerCtx {}
    import ctx._

    assert(transport.recv({
      val hs = new DefaultHttp2Headers
      hs.scheme("testscheme")
      hs.method("TEST")
      hs.path("/")
      hs.authority("auf")
      hs.set("te", "chunked, trailers")
      new DefaultHttp2HeadersFrame(hs, true).streamId(id)
    }))

    val rst = Reset.ProtocolError
    assert(await(reqF.liftToTry) == Throw(rst))
    assert(await(transport.onReset.liftToTry) == Throw(StreamError.Local(rst)))
    assert(transport.isClosed)
  }

  test("server: allows request with `te: trailers`") {
    val ctx = new ServerCtx {}
    import ctx._

    assert(transport.recv({
      val hs = new DefaultHttp2Headers
      hs.scheme("testscheme")
      hs.method("TEST")
      hs.path("/")
      hs.authority("auf")
      hs.set("te", "trailers")
      new DefaultHttp2HeadersFrame(hs, true).streamId(id)
    }))

    assert(await(reqF.liftToTry).isReturn)
  }

}
