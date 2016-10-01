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

  private[this] val readMs = stats.stat("read_ms")
  private[this] val writeMs = stats.stat("write_ms")
  private[this] val serveMs = stats.stat("serve_ms")
  private[this] val activeMs = stats.stat("active_ms")

  private[this] val closed = new AtomicBoolean(false)

  def close(deadline: Time): Future[Unit] =
    if (closed.compareAndSet(false, true)) {
      pending.raise(cancelation)
      Future.join(service.close(deadline), stream.close(deadline)).unit
    } else Future.Unit

  private[this] val pending: Future[Unit] = {
    val startT = Stopwatch.start()
    val acting = stream.read().flatMap { req =>
      val reading = req.onEnd
      reading.onSuccess(_ => readMs.add(startT().inMillis))

      val serveT = Stopwatch.start()
      service(req).flatMap { rsp =>
        serveMs.add(serveT().inMillis)

        val writeT = Stopwatch.start()
        val writing = stream.write(rsp).flatten
        writing.onSuccess(_ => writeMs.add(writeT().inMillis))

        Future.join(reading, writing).unit
      }.before {
        if (closed.compareAndSet(false, true)) {
          Future.join(service.close(), stream.onClose).unit
        } else Future.Unit
      }
    }
    acting.ensure(activeMs.add(startT().inMillis))
    acting
  }
}
