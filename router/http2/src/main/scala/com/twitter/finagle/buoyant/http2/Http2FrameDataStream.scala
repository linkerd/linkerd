package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
import com.twitter.util.{Future, Promise, Return, Throw}
import io.netty.handler.codec.http2._

private[http2] class AQDataStream(q: AsyncQueue[DataStream.Value])
  extends DataStream {

  private[this] val toSeq: DataStream.Value => Seq[DataStream.Value] =
    v => Seq(v)

  def read(): Future[Seq[DataStream.Value]] =
    if (q.size == 0) q.poll().map(toSeq)
    else Future.const(q.drain())

  def fail(exn: Throwable): Future[Unit] = {
    q.fail(exn, discard = true)
    Future.Unit
  }
}
