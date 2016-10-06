package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.util.{Future, Stopwatch, Time}
import io.netty.handler.codec.http2._

private[netty4] trait Netty4H2Writer extends H2Transport.Writer {

  protected[this] def write(f: Http2StreamFrame): Future[Unit]

  /*
   * H2Transport.Writer -- netty4-agnostic h2 message writer
   */

  def write(id: Int, msg: Headers, eos: Boolean): Future[Unit] = {
    val headers = Netty4Message.Headers.extract(msg)
    val frame = new DefaultHttp2HeadersFrame(headers, eos)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }

  def write(id: Int, msg: Message): Future[Future[Unit]] =
    msg.data match {
      case Stream.Nil =>
        write(id, msg.headers, true).map(_ => Future.Unit)
      case data: Stream.Reader =>
        write(id, msg.headers, false).map(_ => streamFrom(id, data))
    }

  private[this] def streamFrom(id: Int, data: Stream.Reader): Future[Unit] =
    if (data.isEmpty) Future.Unit
    else {
      val writeData: Frame => Future[Boolean] = {
        case f: Frame.Data => write(id, f).map(_ => f.isEnd)
        case f: Frame.Trailers => write(id, f).before(Future.True)
      }
      lazy val loop: Boolean => Future[Unit] = {
        case true => Future.Unit
        case false => data.read().flatMap(writeData).flatMap(loop)
      }
      data.read().flatMap(writeData).flatMap(loop)
    }

  def write(id: Int, data: Frame.Data): Future[Unit] =
    write(id, data.buf, data.isEnd)

  def write(id: Int, tlrs: Frame.Trailers): Future[Unit] =
    write(id, tlrs, true /*eos*/ )

  def write(id: Int, buf: Buf, eos: Boolean): Future[Unit] = {
    val bb = BufAsByteBuf.Owned(buf)
    val frame = new DefaultHttp2DataFrame(bb, eos)
    if (id >= 0) frame.setStreamId(id)
    write(frame.retain()).ensure {
      val _ = frame.release()
    }
  }

  def updateWindow(id: Int, incr: Int): Future[Unit] = {
    val frame = new DefaultHttp2WindowUpdateFrame(incr)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }
}

private[netty4] object Netty4H2Writer {

  def apply(transport: Transport[Http2StreamFrame, Http2StreamFrame]): H2Transport.Writer =
    new Netty4H2Writer {
      protected[this] def write(f: Http2StreamFrame) = transport.write(f)
    }
}
