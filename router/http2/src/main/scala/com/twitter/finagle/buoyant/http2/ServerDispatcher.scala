package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.{CancelledRequestException, Service}
import com.twitter.util.{Closable, Future, Time}

class ServerDispatcher(
  stream: ServerStreamTransport,
  service: Service[Request, Response]
) extends Closable {

  private[this] val log = com.twitter.logging.Logger.get(getClass.getName)
  private[this] lazy val cancelation = new CancelledRequestException

  @volatile private[this] var finished: Boolean = false

  val pending: Future[Unit] =
    for {
      req <- stream.read()
      rsp <- service(req)
      writing <- stream.write(rsp)
      _ <- {
        val reading = req.data match {
          case None => Future.Unit
          case Some(data) => data.onEnd
        }
        Future.join(reading, writing)
      }
      _ <- {
        finished = true
        log.info("closing server stream")
        service.close().join(stream.close()).unit
      }
    } yield ()

  def close(deadline: Time): Future[Unit] =
    if (finished) Future.Unit
    else {
      log.info("closing server dispatcher")
      pending.raise(cancelation)
      service.close(deadline).join(stream.close(deadline)).unit
    }
}
