package com.twitter.finagle.buoyant.http2

import com.twitter.util.{Closable, Future}

trait ServerStreamTransport extends Closable {
  def onClose: Future[Throwable]
  def read(): Future[Request]
  def write(rsp: Response): Future[Future[Unit]]
}
