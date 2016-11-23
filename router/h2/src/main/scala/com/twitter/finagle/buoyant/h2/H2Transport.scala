package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.util.{Closable, Future, Time}
import java.net.SocketAddress

object H2Transport {

  /**
   * A codec-agnostic interface supporting writes of H2 messages to a transport.
   */
  trait Writer {

    def localAddress: SocketAddress
    def remoteAddress: SocketAddress

    def write(id: Int, orig: Headers, eos: Boolean): Future[Unit]
    def write(id: Int, buf: Buf, eos: Boolean): Future[Unit]
    def write(id: Int, frame: Frame): Future[Unit]

    /**
     * Update the flow control window by `incr` bytes.
     */
    def updateWindow(id: Int, incr: Int): Future[Unit]

    /**
     * Write a stream reset.
     */
    def reset(id: Int, err: Reset): Future[Unit]

    /**
     * Write a GO_AWAY frame and close the connection..
     */
    def goAway(err: GoAway, deadline: Time): Future[Unit]

    final def goAway(err: GoAway): Future[Unit] = goAway(err, Time.Top)
  }
}
