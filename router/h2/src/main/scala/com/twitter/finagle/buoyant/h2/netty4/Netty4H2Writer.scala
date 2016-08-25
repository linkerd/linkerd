package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.util.{Future, Stopwatch, Time}
import io.netty.handler.codec.http2._

private[netty4] class Netty4H2Writer(
  transport: Transport[Http2StreamFrame, Http2StreamFrame]
) extends H2Transport.Writer {

  /*
   * H2Transport.Writer -- netty4-agnostic h2 message writer
   */

  def write(id: Int, msg: Headers, eos: Boolean): Future[Unit] = {
    val headers = Netty4Message.extract(msg)
    val frame = new DefaultHttp2HeadersFrame(headers, eos)
    if (id >= 0) frame.setStreamId(id)
    transport.write(frame)
  }

  def write(id: Int, msg: Message): Future[Unit] =
    write(id, msg, msg.isEmpty)

  def write(id: Int, data: DataStream.Data): Future[Unit] =
    write(id, data.buf, data.isEnd)

  def write(id: Int, tlrs: DataStream.Trailers): Future[Unit] =
    write(id, tlrs, true /*eos*/ )

  def write(id: Int, buf: Buf, eos: Boolean): Future[Unit] = {
    val bb = BufAsByteBuf.Owned(buf)
    val frame = new DefaultHttp2DataFrame(bb, eos)
    if (id >= 0) frame.setStreamId(id)
    transport.write(frame)
  }

  def updateWindow(id: Int, incr: Int): Future[Unit] = {
    val frame = new DefaultHttp2WindowUpdateFrame(incr)
    if (id >= 0) frame.setStreamId(id)
    transport.write(frame)
  }
}
