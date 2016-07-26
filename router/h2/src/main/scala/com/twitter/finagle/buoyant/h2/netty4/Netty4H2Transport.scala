package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.{Transport, TransportProxy}
import com.twitter.io.Buf
import com.twitter.util.{Future, Stopwatch, Time}
import io.netty.handler.codec.http2._

class Netty4Http2Transport(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends TransportProxy[Http2StreamFrame, Http2StreamFrame](transport)
  with H2Transport.Writer {

  // private[this] val log = com.twitter.logging.Logger.get(getClass.getName)

  private[this] val transportReadUs = statsReceiver.stat("read_us")
  private[this] val transportWriteUs = statsReceiver.stat("write_us")

  override def read(): Future[Http2StreamFrame] = {
    val t0 = Stopwatch.start()
    val rx = transport.read()
    rx.onSuccess(_ => transportReadUs.add(t0().inMicroseconds))
    rx
  }

  override def write(f: Http2StreamFrame): Future[Unit] = {
    val t0 = Stopwatch.start()
    val tx = transport.write(f)
    tx.onSuccess(_ => transportWriteUs.add(t0().inMicroseconds))
    tx
  }

  def write(id: Int, msg: Headers, eos: Boolean): Future[Unit] = {
    val frame = new DefaultHttp2HeadersFrame(Netty4Message.extract(msg), eos)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
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
    write(frame)
  }

  def updateWindow(id: Int, incr: Int): Future[Unit] = {
    val frame = new DefaultHttp2WindowUpdateFrame(incr)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }
}
