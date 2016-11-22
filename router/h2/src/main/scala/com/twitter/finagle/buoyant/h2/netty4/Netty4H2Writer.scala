package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.WriteException
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util.{Future, NonFatal, Stopwatch, Time}
import io.netty.handler.codec.http2._

private[netty4] trait Netty4H2Writer extends H2Transport.Writer {
  import Netty4H2Writer.log

  protected[this] def write(f: Http2Frame): Future[Unit]
  protected[this] def close(deadline: Time): Future[Unit]

  /*
   * H2Transport.Writer -- netty4-agnostic h2 message writer
   */

  override def write(id: Int, msg: Headers, eos: Boolean): Future[Unit] = {
    val headers = Netty4Message.Headers.extract(msg)
    val frame = new DefaultHttp2HeadersFrame(headers, eos)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }

  override def write(id: Int, f: Frame): Future[Unit] = f match {
    case data: Frame.Data => write(id, data.buf, data.isEnd)
    case tlrs: Frame.Trailers => write(id, tlrs, true /*eos*/ )
  }

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

  override def reset(id: Int, err: Reset): Future[Unit] = {
    require(id > 0)
    val code = err match {
      case Reset.Cancel => Http2Error.CANCEL
      case Reset.EnhanceYourCalm => Http2Error.ENHANCE_YOUR_CALM
      case Reset.InternalError => Http2Error.INTERNAL_ERROR
      case Reset.NoError => Http2Error.NO_ERROR
      case Reset.Refused => Http2Error.REFUSED_STREAM
      case Reset.Closed => Http2Error.STREAM_CLOSED
    }
    val frame = new DefaultHttp2ResetFrame(code).setStreamId(id)
    write(frame)
  }

  /*
   * Connection errors
   */

  // private[this] def goAwayFrame(err: GoAway): Http2GoAwayFrame = {
  //   val code = err match {
  //     case GoAway.EnhanceYourCalm => Http2Error.ENHANCE_YOUR_CALM
  //     case GoAway.InternalError => Http2Error.INTERNAL_ERROR
  //     case GoAway.NoError => Http2Error.NO_ERROR
  //     case GoAway.ProtocolError => Http2Error.PROTOCOL_ERROR
  //   }
  //   new DefaultHttp2GoAwayFrame(code)
  // }

  override def goAway(err: GoAway, deadline: Time): Future[Unit] = {
    // Our version of netty has a bug that prevents us from sending
    // AWAYs, so just close until that's fixed.
    // write(goAwayFrame(err))
    close(deadline)
  }
}

private[netty4] object Netty4H2Writer {
  private val log = Logger.get(getClass.getName)

  private val wrapWriteException: PartialFunction[Throwable, Future[Nothing]] = {
    case exc: Throwable => Future.exception(WriteException(exc))
  }

  def apply(trans: Transport[Http2Frame, Http2Frame]): H2Transport.Writer =
    new Netty4H2Writer {
      override protected[this] def write(frame: Http2Frame): Future[Unit] =
        trans.write(frame).rescue(wrapWriteException)

      override protected[this] def close(t: Time): Future[Unit] =
        trans.close(t)
    }
}
