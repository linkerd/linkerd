package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.util.Future

object H2Transport {

  /**
   * A codec-agnostic interface supporting writes of H2 messages to a transport.
   */
  trait Writer {
    def write(id: Int, msg: Message): Future[Future[Unit]]
    def write(id: Int, orig: Headers, eos: Boolean): Future[Unit]
    def write(id: Int, buf: Buf, eos: Boolean): Future[Unit]
    def write(id: Int, data: Frame.Data): Future[Unit]
    def write(id: Int, tlrs: Frame.Trailers): Future[Unit]

    def updateWindow(id: Int, incr: Int): Future[Unit]
  }
}
