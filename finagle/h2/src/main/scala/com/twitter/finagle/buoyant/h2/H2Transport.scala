package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.util.{Closable, Future, Time}
import java.net.SocketAddress

import io.netty.handler.codec.http2.{Http2SettingsFrame, Http2Stream}

object H2Transport {

  /**
   * A codec-agnostic interface supporting writes of H2 messages to a transport.
   */
  trait Writer {

    def localAddress: SocketAddress
    def remoteAddress: SocketAddress

    def write(id: Int, streamState: Http2Stream.State, orig: Headers, eos: Boolean): Future[Unit]
    def write(id: Int, streamState: Http2Stream.State, buf: Buf, eos: Boolean): Future[Unit]
    def write(id: Int, streamState: Http2Stream.State, frame: Frame): Future[Unit]

    /**
     * Update the flow control window by `incr` bytes.
     */
    def updateWindow(id: Int, streamState: Http2Stream.State, incr: Int): Future[Unit]

    /**
     * Write a stream reset.
     */
    def reset(id: Int, streamState: Http2Stream.State, err: Reset): Future[Unit]

    /**
     * Write a GO_AWAY frame and close the connection..
     */
    def goAway(err: GoAway, deadline: Time): Future[Unit]

    final def goAway(err: GoAway): Future[Unit] = goAway(err, Time.Top)
  }
}
