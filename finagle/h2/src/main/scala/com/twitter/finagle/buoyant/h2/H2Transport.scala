package com.twitter.finagle.buoyant.h2

import com.twitter.util.{Future, Time}
import io.netty.buffer.ByteBuf
import java.net.SocketAddress
import io.netty.handler.codec.http2.H2FrameStream

object H2Transport {

  /**
   * A codec-agnostic interface supporting writes of H2 messages to a transport.
   * H2 FrameStream is a representation of a stream i.e. the stream's identifier and
   * the stream's state.
   */
  trait Writer {

    def localAddress: SocketAddress
    def remoteAddress: SocketAddress

    def write(frameStream: H2FrameStream, orig: Headers, eos: Boolean): Future[Unit]
    def write(frameStream: H2FrameStream, buf: ByteBuf, eos: Boolean): Future[Unit]
    def write(frameStream: H2FrameStream, frame: Frame): Future[Unit]

    /**
     * Update the flow control window by `incr` bytes.
     */
    def updateWindow(frameStream: H2FrameStream, incr: Int): Future[Unit]

    /**
     * Write a stream reset.
     */
    def reset(frameStream: H2FrameStream, err: Reset): Future[Unit]

    /**
     * Write a GO_AWAY frame and close the connection..
     */
    def goAway(err: GoAway, deadline: Time): Future[Unit]

    final def goAway(err: GoAway): Future[Unit] = goAway(err, Time.Top)
  }
}
