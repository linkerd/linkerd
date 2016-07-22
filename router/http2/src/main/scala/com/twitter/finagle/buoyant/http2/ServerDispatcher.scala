package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.{CancelledRequestException, Service}
import com.twitter.util.{Closable, Future, Time}

class ServerDispatcher(
  stream: ServerStreamTransport,
  service: Service[Request, Response]
) extends Closable {

  private[this] lazy val cancelation = new CancelledRequestException

  @volatile private[this] var finished: Boolean = false

  val pending: Future[Unit] =
    for {
      req <- stream.read()
      rsp <- service(req)
      writing <- stream.write(rsp)
      _ <- writing
      _ <- service.close().join(stream.close()).unit
    } yield {
      finished = true
    }

  def close(deadline: Time): Future[Unit] =
    if (finished) Future.Unit
    else {
      pending.raise(cancelation)
      service.close(deadline).join(stream.close(deadline)).unit
    }
}
