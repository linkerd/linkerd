package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.util.{Closable, Future, Time}

object H2Transport {

  /**
   * A codec-agnostic interface supporting writes of H2 messages to a transport.
   */
  trait Writer extends Closable {

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

    /*
     * Connection errors: emit GOAWAY and close connection.
     */

    def goAwayNoError(deadline: Time): Future[Unit]
    def goAwayProtocolError(deadline: Time): Future[Unit]
    def goAwayInternalError(deadline: Time): Future[Unit]
    def goAwayChillBro(deadline: Time): Future[Unit]

    /*
     * Stream errors
     */

    def resetNoError(id: Int): Future[Unit]
    def resetInternalError(id: Int): Future[Unit]
    def resetRefused(id: Int): Future[Unit]
    def resetStreamClosed(id: Int): Future[Unit]
    def resetCancel(id: Int): Future[Unit]
    def resetChillBro(id: Int): Future[Unit]
  }
}
