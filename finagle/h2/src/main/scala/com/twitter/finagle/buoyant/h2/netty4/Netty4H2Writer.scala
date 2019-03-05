package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.WriteException
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time}
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http2._
import java.net.SocketAddress

private[netty4] trait Netty4H2Writer extends H2Transport.Writer {

  protected[this] def write(f: Http2Frame): Future[Unit]
  protected[this] def close(deadline: Time): Future[Unit]

  /*
   * H2Transport.Writer -- netty4-agnostic h2 message writer
   */

  override def write(stream: H2FrameStream, msg: Headers, eos: Boolean): Future[Unit] = {
    val headers = Netty4Message.Headers.extract(msg)
    val frame = new DefaultHttp2HeadersFrame(headers, eos)
    if (stream.id >= 0) frame.stream(stream)
    write(frame)
  }

  override def write(stream: H2FrameStream, f: Frame): Future[Unit] = f match {
    case data: Frame.Data => write(stream, data.buf, data.isEnd)
    case tlrs: Frame.Trailers => write(stream, tlrs, eos = true)
  }

  override def write(stream: H2FrameStream, buf: ByteBuf, eos: Boolean): Future[Unit] = {
    val nettyFrame = new DefaultHttp2DataFrame(buf.duplicate, eos)
    if (stream.id >= 0) nettyFrame.stream(stream)
    write(nettyFrame.retain())
  }

  override def updateWindow(stream: H2FrameStream, incr: Int): Future[Unit] = {
    val frame = new DefaultHttp2WindowUpdateFrame(incr)
    if (stream.id >= 0) frame.stream(stream)
    write(frame)
  }

  override def reset(stream: H2FrameStream, rst: Reset): Future[Unit] = {
    require(stream.id > 0)
    val code = Netty4Message.Reset.toHttp2Error(rst)
    val frame = new DefaultHttp2ResetFrame(code).stream(stream)
    write(frame)
  }

  override def sendPing(): Future[Unit] = {
    val frame = new DefaultHttp2PingFrame(0)
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
    // XXX Our version of netty has a bug that prevents us from
    // sending AWAYs, so just close until that's fixed.
    // See: https://github.com/netty/netty/issues/5307
    //write(goAwayFrame(err))

    close(deadline)
  }
}

private[netty4] object Netty4H2Writer {
  private val log = Logger.get("h2")

  private val wrapWriteException: PartialFunction[Throwable, Future[Nothing]] = {
    case exc: Throwable => Future.exception(WriteException(exc))
  }

  def apply(trans: Transport[Http2Frame, Http2Frame]): H2Transport.Writer =
    new Netty4H2Writer {
      override protected[this] def write(frame: Http2Frame): Future[Unit] = {
        log.trace("[L:%s R:%s] write: %s", trans.context.localAddress, trans.context.remoteAddress, frame.name)
        val f = trans.write(frame).rescue(wrapWriteException)
        f.respond(v => log.trace("[L:%s R:%s] wrote: %s: %s", trans.context.localAddress, trans.context.remoteAddress, frame.name, v))
        f
      }

      override protected[this] def close(t: Time): Future[Unit] =
        trans.close(t)

      override def localAddress: SocketAddress = trans.context.localAddress
      override def remoteAddress: SocketAddress = trans.context.remoteAddress
    }
}
