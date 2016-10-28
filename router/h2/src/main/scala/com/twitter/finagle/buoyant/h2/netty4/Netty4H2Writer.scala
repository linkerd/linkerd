package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util.{Future, Stopwatch, Time}
import io.netty.handler.codec.http2._

private[netty4] trait Netty4H2Writer extends H2Transport.Writer {
  import Netty4H2Writer.log

  protected[this] def write(f: Http2Frame): Future[Unit]

  /*
   * H2Transport.Writer -- netty4-agnostic h2 message writer
   */

  override def write(id: Int, msg: Headers, eos: Boolean): Future[Unit] = {
    val headers = Netty4Message.Headers.extract(msg)
    val frame = new DefaultHttp2HeadersFrame(headers, eos)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }

  override def writeAll(id: Int, msg: Message): Future[Future[Unit]] =
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
        case f: Frame.Data => write(id, f).before(f.release()).map(_ => f.isEnd)
        case f: Frame.Trailers => write(id, f).before(Future.True)
      }
      lazy val loop: Boolean => Future[Unit] = {
        case true => Future.Unit
        case false => data.read().flatMap(writeData).flatMap(loop)
      }
      data.read().flatMap(writeData).flatMap(loop)
    }

  override def write(id: Int, data: Frame.Data): Future[Unit] =
    write(id, data.buf, data.isEnd)

  override def write(id: Int, tlrs: Frame.Trailers): Future[Unit] =
    write(id, tlrs, true /*eos*/ )

  override def write(id: Int, buf: Buf, eos: Boolean): Future[Unit] = {
    val bb = BufAsByteBuf.Owned(buf)
    val frame = new DefaultHttp2DataFrame(bb, eos)
    if (id >= 0) frame.setStreamId(id)
    write(frame.retain()).ensure {
      // just for reference-counting, not flow control.
      frame.release(); ()
    }
  }

  override def updateWindow(id: Int, incr: Int): Future[Unit] = {
    val frame = new DefaultHttp2WindowUpdateFrame(incr)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }

  /*
   * Connection errors
   */

  override def goAwayNoError(deadline: Time): Future[Unit] =
    goAwayAndClose(Http2Error.NO_ERROR, deadline)

  override def goAwayProtocolError(deadline: Time): Future[Unit] =
    goAwayAndClose(Http2Error.PROTOCOL_ERROR, deadline)

  override def goAwayInternalError(deadline: Time): Future[Unit] =
    goAwayAndClose(Http2Error.INTERNAL_ERROR, deadline)

  override def goAwayChillBro(deadline: Time): Future[Unit] =
    goAwayAndClose(Http2Error.ENHANCE_YOUR_CALM, deadline)

  private[this] def goAwayAndClose(code: Http2Error, deadline: Time): Future[Unit] = {
    write(new DefaultHttp2GoAwayFrame(code))
      .before(close(deadline))
  }

  /*
   * Stream errors
   */

  override def resetNoError(id: Int): Future[Unit] =
    writeReset(id, Http2Error.NO_ERROR)

  override def resetInternalError(id: Int): Future[Unit] =
    writeReset(id, Http2Error.INTERNAL_ERROR)

  override def resetRefused(id: Int): Future[Unit] =
    writeReset(id, Http2Error.REFUSED_STREAM)

  override def resetStreamClosed(id: Int): Future[Unit] =
    writeReset(id, Http2Error.STREAM_CLOSED)

  override def resetCancel(id: Int): Future[Unit] =
    writeReset(id, Http2Error.CANCEL)

  override def resetChillBro(id: Int): Future[Unit] =
    writeReset(id, Http2Error.ENHANCE_YOUR_CALM)

  private[this] def writeReset(id: Int, code: Http2Error): Future[Unit] = {
    require(id > 0)
    write(new DefaultHttp2ResetFrame(code).setStreamId(id))
  }

}

private[netty4] object Netty4H2Writer {
  private val log = Logger.get(getClass.getName)

  def apply(trans: Transport[Http2Frame, Http2Frame]): H2Transport.Writer =
    new Netty4H2Writer {
      override protected[this] def write(f: Http2Frame): Future[Unit] = trans.write(f)
      override def close(t: Time): Future[Unit] = trans.close(t)
    }
}
