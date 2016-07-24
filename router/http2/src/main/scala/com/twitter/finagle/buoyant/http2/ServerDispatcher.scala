package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.{CancelledRequestException, Service}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Closable, Future, Stopwatch, Time}

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

  @volatile private[this] var finished: Boolean = false

  val pending: Future[Unit] = {
    val serveT0 = Stopwatch.start()
    val serving =
      stream.read().flatMap { req =>
        service(req).flatMap(stream.write(_)).flatMap { writing =>
          val streamT0 = Stopwatch.start()
          val reading = req.data match {
            case None => Future.Unit
            case Some(data) => data.onEnd
          }
          val done = Future.join(reading, writing).unit
          done.ensure(streamingMillis.add(streamT0().inMillis))
          done
        }
      }.before {
        finished = true
        Future.join(service.close(), stream.close()).unit
      }
    serving.ensure(servingMillis.add(serveT0().inMillis))
    serving
  }

  def close(deadline: Time): Future[Unit] =
    if (finished) Future.Unit
    else {
      finished = true
      pending.raise(cancelation)
      Future.join(service.close(deadline), stream.close(deadline)).unit
    }
}
