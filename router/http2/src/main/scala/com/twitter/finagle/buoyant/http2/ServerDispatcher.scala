package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.{CancelledRequestException, Service}
import com.twitter.util.{Closable, Future, Time}

class ServerDispatcher(
  stream: ServerStreamTransport,
  service: Service[Request, Response]
) extends Closable {

  private[this] lazy val cancelation = new CancelledRequestException

  val pending: Future[Unit] =
    for {
      req <- stream.read()
      rsp <- service(req)
      writing <- stream.write(rsp)
      finished <- writing
    } yield finished

  def close(deadline: Time): Future[Unit] = {
    pending.raise(cancelation)
    service.close(deadline).join(stream.close(deadline)).unit
  }
}
