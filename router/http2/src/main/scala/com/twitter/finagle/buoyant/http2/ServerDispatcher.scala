package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.{CancelledRequestException, Service}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Closable, Future, Stopwatch, Time}
import java.util.concurrent.atomic.AtomicBoolean

object ServerDispatcher {
  private lazy val cancelation = new CancelledRequestException
}

class ServerDispatcher(
  stream: ServerStreamTransport,
  service: Service[Request, Response],
  stats: StatsReceiver = NullStatsReceiver
) extends Closable {

  import ServerDispatcher._

  private[this] val streamingMillis = stats.stat("streaming_ms")
  private[this] val servingMillis = stats.stat("serving_ms")

  private[this] val closed = new AtomicBoolean(false)

  val pending: Future[Unit] = {
    val t0 = Stopwatch.start()
    val serving =
      stream.read().flatMap { req =>
        service(req).flatMap(stream.write(_)).flatMap { writing =>
          val t1 = Stopwatch.start()
          val done = Future.join(req.onEnd, writing).unit
          done.ensure(streamingMillis.add(t1().inMillis))
          done
        }
      }.before {
        if (closed.compareAndSet(false, true)) {
          Future.join(service.close(), stream.close()).unit
        } else Future.Unit
      }
    serving.ensure(servingMillis.add(t0().inMillis))
    serving
  }

  def close(deadline: Time): Future[Unit] =
    if (closed.compareAndSet(false, true)) {
      pending.raise(cancelation)
      Future.join(service.close(deadline), stream.close(deadline)).unit
    } else Future.Unit
}
