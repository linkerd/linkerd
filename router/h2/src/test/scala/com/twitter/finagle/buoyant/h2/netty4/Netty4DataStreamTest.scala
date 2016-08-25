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

  test("without accumulation") {
    var released = 0L
    val releaser: Netty4DataStream.Releaser = { bytes =>
      released += bytes
      Future.Unit
    }

    val frameq = new AsyncQueue[Http2StreamFrame]
    val stream = new Netty4DataStream(frameq, releaser, Int.MaxValue)

    assert(!stream.isEmpty)
    val endF = stream.onEnd
    assert(!endF.isDefined)
    val read0 = stream.read()
    assert(!read0.isDefined)

    val buf = Buf.Utf8("hi my name is brak")

    assert(frameq.offer(new DefaultHttp2DataFrame(BufAsByteBuf.Shared(buf), false)))
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

    assert(frameq.offer(new DefaultHttp2DataFrame(BufAsByteBuf.Shared(buf), true)))
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
}
