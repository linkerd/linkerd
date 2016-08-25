package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.util.Future

object H2Transport {

  /**
   * A codec-agnostic interface supporting writes of H2 messages to a transport.
   */
  trait Writer {
    def write(id: Int, orig: Headers, eos: Boolean): Future[Unit]

    def write(id: Int, orig: Message): Future[Unit]
    def write(id: Int, tlrs: DataStream.Trailers): Future[Unit]

    def write(id: Int, buf: Buf, eos: Boolean): Future[Unit]
    def write(id: Int, data: DataStream.Data): Future[Unit]
    def updateWindow(id: Int, incr: Int): Future[Unit]
  }
}
