package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.util.Future

object H2Transport {

  /**
   * A codec-agnostic interface supporting writes of H2 messages to a transport.
   */
  trait Writer {

    /**
     * Write an entire message and its Stream.
     *
     * The outer Future is satisfied when the message's headers have
     * been written to the transport.  This Future is satisfied with a
     * second, inner Future that is satisfied when the message's
     * stream has been completely written to the transport.
     */
    def writeAll(id: Int, msg: Message): Future[Future[Unit]]

    def write(id: Int, orig: Headers, eos: Boolean): Future[Unit]
    def write(id: Int, buf: Buf, eos: Boolean): Future[Unit]
    def write(id: Int, data: Frame.Data): Future[Unit]
    def write(id: Int, tlrs: Frame.Trailers): Future[Unit]

    /**
     * Update the flow control window by `incr` bytes.
     */
    def updateWindow(id: Int, incr: Int): Future[Unit]
  }
}
