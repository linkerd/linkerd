package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.{CancelledRequestException, Service}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Closable, Future, Stopwatch, Time}
import java.util.concurrent.atomic.AtomicBoolean

object ServerDispatcher {
  private lazy val cancelation = new CancelledRequestException
}

/**
 * Reads a [[Request]] from `stream`
 *
 * Does NOT admit PUSH_PROMISE messages from the server.
 */
class ServerDispatcher(
  stream: ServerStreamTransport,
  service: Service[Request, Response],
  stats: StatsReceiver = NullStatsReceiver
) extends Closable {

  import ServerDispatcher._

  private[this] val streamingMillis = stats.stat("streaming_ms")
  private[this] val servingMillis = stats.stat("serving_ms")

  private[this] val closed = new AtomicBoolean(false)

  def close(deadline: Time): Future[Unit] =
    if (closed.compareAndSet(false, true)) {
      pending.raise(cancelation)
      Future.join(service.close(deadline), stream.close(deadline)).unit
    } else Future.Unit

  private[this] val pending: Future[Unit] = {
    val t0 = Stopwatch.start()
    val serving =
      stream.read().flatMap { req =>
        service(req).flatMap { rsp =>
          stream.write(rsp).flatMap { writing =>
            val t1 = Stopwatch.start()
            val done = Future.join(req.onEnd, writing).unit
            done.ensure(streamingMillis.add(t1().inMillis))
            done.before {
              if (closed.compareAndSet(false, true)) {
                Future.join(service.close(), stream.onClose).unit
              } else Future.Unit
            }
          }
        }
      }
    serving.ensure(servingMillis.add(t0().inMillis))
    serving
  }
}
