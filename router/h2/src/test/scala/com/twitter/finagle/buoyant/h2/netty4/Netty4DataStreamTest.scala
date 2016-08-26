package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.BufAsByteBuf
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.Awaits
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http2._
import org.scalatest.FunSuite

class Netty4H2DataStreamTest extends FunSuite with Awaits {

  private def mkFrame(buf: Buf, eos: Boolean = false) =
    new DefaultHttp2DataFrame(BufAsByteBuf.Shared(buf), eos)

  private class Ctx(minAccrual: Int) {
    var released = 0L
    val releaser: Netty4DataStream.Releaser = { bytes =>
      released += bytes
      Future.Unit
    }

    val stream = new Netty4DataStream(releaser, minAccrual)
  }

  test("without accumulation") {
    val ctx = new Ctx(Int.MaxValue)
    import ctx._

    assert(!stream.isEmpty)
    val endF = stream.onEnd
    assert(!endF.isDefined)
    val read0 = stream.read()
    assert(!read0.isDefined)

    val buf = Buf.Utf8("hi my name is brak")

    assert(stream.offer(mkFrame(buf)))
    assert(read0.isDefined)
    await(read0) match {
      case data: DataStream.Data =>
        assert(data.buf == buf)
        assert(released == 0)
        await(data.release())
        assert(released == buf.length)

      case frame =>
        fail(s"unexpected frame: $frame")
    }
    assert(!endF.isDefined)

    assert(stream.offer(mkFrame(buf, eos = true)))
    assert(!endF.isDefined)
    val read1 = stream.read()
    assert(read1.isDefined)
    await(read1) match {
      case data: DataStream.Data =>
        assert(data.buf == buf)
        assert(released == buf.length)
        await(data.release())
        assert(released == buf.length * 2)

      case frame =>
        fail(s"unexpected frame: $frame")
    }
    assert(endF.isDefined)
  }

  test("with accumulation") {
    val ctx = new Ctx(3)
    import ctx._

    val buf = Buf.Utf8("i'm zorak")

    assert(!stream.isEmpty)
    val endF = stream.onEnd
    assert(!endF.isDefined)

    assert(stream.offer(mkFrame(buf)))
    assert(stream.offer(mkFrame(buf)))

    info("reading data")
    val read0 = stream.read()
    assert(read0.isDefined)
    await(read0) match {
      case data: DataStream.Data =>
        assert(data.buf == buf)
        assert(released == 0)
        await(data.release())
        assert(released == buf.length)

      case frame =>
        fail(s"unexpected frame: $frame")
    }
    assert(!endF.isDefined)

    info("reading data")
    val read1 = stream.read()
    assert(read1.isDefined)
    await(read1) match {
      case data: DataStream.Data =>
        assert(data.buf == buf)
        assert(released == buf.length)
        await(data.release())
        assert(released == buf.length * 2)

      case frame =>
        fail(s"unexpected frame: $frame")
    }
    assert(!endF.isDefined)

    assert(stream.offer(mkFrame(buf)))
    assert(stream.offer(mkFrame(buf)))
    assert(stream.offer(mkFrame(buf, eos = true)))
    assert(!endF.isDefined)

    info("reading data")
    val read2 = stream.read()
    assert(read2.isDefined)
    await(read2) match {
      case data: DataStream.Data =>
        assert(data.buf == buf.concat(buf).concat(buf))
        assert(released == buf.length * 2)
        await(data.release())
        assert(released == buf.length * 5)

      case frame =>
        fail(s"unexpected frame: $frame")
    }
    assert(endF.isDefined)
  }

  test("with accumulation and trailers") {
    val ctx = new Ctx(2)
    import ctx._

    val buf = Buf.Utf8("me moltar")

    assert(!stream.isEmpty)
    val endF = stream.onEnd
    assert(!endF.isDefined)

    assert(stream.offer(mkFrame(buf)))
    assert(stream.offer(mkFrame(buf)))
    assert(stream.offer({
      // Trailers
      val h = new DefaultHttp2Headers()
      h.set("me", "moltar")
      new DefaultHttp2HeadersFrame(h, true)
    }))

    info("reading data")
    val read0 = stream.read()
    assert(read0.isDefined)
    await(read0) match {
      case data: DataStream.Data =>
        assert(data.buf == buf.concat(buf))
        assert(released == 0)
        await(data.release())
        assert(released == buf.length * 2)
      case frame =>
        fail(s"unexpected frame: $frame")
    }
    assert(!endF.isDefined)

    info("reading trailers")
    val read1 = stream.read()
    assert(read1.isDefined)
    await(read1) match {
      case trailers: DataStream.Trailers =>
        assert(trailers.headers == Seq("me" -> "moltar"))
      case frame =>
        fail(s"unexpected frame: $frame")
    }
    assert(endF.isDefined)
  }
}
