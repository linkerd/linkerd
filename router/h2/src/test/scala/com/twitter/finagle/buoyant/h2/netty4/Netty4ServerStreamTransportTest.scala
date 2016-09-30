package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.BufAsByteBuf
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Time}
import io.netty.handler.codec.http2._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class Netty4ServerStreamTransportTest extends FunSuite with Awaits {

  class TestTransport extends Transport[Http2StreamFrame, Http2StreamFrame] {
    val recvq, sentq = new AsyncQueue[Http2StreamFrame]
    val closeP = new Promise[Throwable]

    def status = ???
    def localAddress = ???
    def remoteAddress = ???
    def peerCertificate = ???
    def read(): Future[Http2StreamFrame] = recvq.poll()
    def write(f: Http2StreamFrame): Future[Unit] = {
      sentq.offer(f)
      Future.Unit
    }
    def onClose = closeP
    def close(d: Time): Future[Unit] = {
      closeP.setValue(new Exception)
      Future.Unit
    }
  }

  test("reads a request from the underlying transport") {
    val trans = new TestTransport
    val stream = new Netty4ServerStreamTransport(trans)
    val reqf = stream.read()
    assert(!reqf.isDefined)

    assert(trans.sentq.size == 0)
    trans.recvq.offer({
      val hs = new DefaultHttp2Headers
      hs.scheme("h2")
      hs.method("SUP")
      hs.path("/")
      hs.authority("auf")
      new DefaultHttp2HeadersFrame(hs, false)
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
    trans.recvq.offer(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(Buf.Utf8("data")), false))
    assert(d0f.isDefined)
    await(d0f) match {
      case f: Frame.Data =>
        assert(f.buf == Buf.Utf8("data"))
        assert(!f.isEnd)
      case f =>
        fail(s"unexpected frame: $f")
    }

    val d1f = data.read()
    assert(!d1f.isDefined)
    trans.recvq.offer({
      val hs = new DefaultHttp2Headers
      hs.set("trailers", "chya")
      new DefaultHttp2HeadersFrame(hs, true)
    })
    assert(d1f.isDefined)
    await(d1f) match {
      case f: Frame.Trailers =>
        assert(f.toSeq == Seq("trailers" -> "chya"))
        assert(f.isEnd)
      case f =>
        fail(s"unexpected frame: $f")
    }
  }

  test("writes a response on the underlying transport") {
    val trans = new TestTransport
    val stream = new Netty4ServerStreamTransport(trans)

    val rspdata = new Netty4Stream(_ => Future.Unit)
    val rsp = {
      val hs = new DefaultHttp2Headers
      hs.status("202")
      Netty4Message.Response(hs, rspdata)
    }

    val wf = stream.write(rsp)
    assert(wf.isDefined)
    val endf = await(wf)
    assert(!endf.isDefined)

    assert(await(trans.sentq.poll()) == {
      val hs = new DefaultHttp2Headers
      hs.status("202")
      new DefaultHttp2HeadersFrame(hs, false)
    })

    val buf = Buf.Utf8("Looks like some tests failed.")
    rspdata.write(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf).retain(), false))
    assert(await(trans.sentq.poll()) == new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false))

    rspdata.write(new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf).retain(), false))
    assert(await(trans.sentq.poll()) == new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false))

    val trailers = new DefaultHttp2Headers
    trailers.set("trailin", "yams")
    rspdata.write(new DefaultHttp2HeadersFrame(trailers, true))
    assert(await(trans.sentq.poll()) == new DefaultHttp2HeadersFrame(trailers, true))
  }
}
