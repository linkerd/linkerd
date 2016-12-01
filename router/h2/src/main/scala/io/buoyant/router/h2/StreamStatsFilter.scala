package io.buoyant.router.h2

import com.twitter.finagle.{Status => _, _}
import com.twitter.finagle.buoyant.h2.{Request, Response, Stream}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.{Future, Return, Stopwatch, Throw}

object StreamStatsFilter {
  val role = Stack.Role("StreamStatsFilter")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Stats, ServiceFactory[Request, Response]] {
      override def role = StreamStatsFilter.role
      override def description = "Record stats on h2 streams"
      override def make(statsP: param.Stats, next: ServiceFactory[Request, Response]) = {
        val param.Stats(stats) = statsP
        new StreamStatsFilter(stats).andThen(next)
      }
    }
}

class StreamStatsFilter(statsReceiver: StatsReceiver)
  extends SimpleFilter[Request, Response] {

  private[this] val reqStreamTimeMs = statsReceiver.stat("stream", "request", "duration_ms")
  private[this] val rspStreamTimeMs = statsReceiver.stat("stream", "response", "duration_ms")
  private[this] val streamTimeMs = statsReceiver.stat("stream", "duration_ms")
  private[this] val reqStreamFailures = statsReceiver.counter("stream", "request", "failures")
  private[this] val rspStreamFailures = statsReceiver.counter("stream", "response", "failures")
  private[this] val streamFailures = statsReceiver.counter("stream", "failures")

  private[this] val latencyMs = statsReceiver.stat("latency_ms")

  override def apply(req: Request, service: Service[Request, Response]): Future[Response] = {
    val reqT = Stopwatch.start()
    req.stream.onEnd.respond {
      case Return(_) => reqStreamTimeMs.add(reqT().inMillis)
      case Throw(_) => reqStreamFailures.incr()
    }

    val rspF = service(req)

    rspF.onSuccess { rsp =>
      latencyMs.add(reqT().inMillis)

      val rspT = Stopwatch.start()
      rsp.stream.onEnd.respond {
        case Return(_) => rspStreamTimeMs.add(rspT().inMillis)
        case Throw(_) => rspStreamFailures.incr()
      }

      val _ = req.stream.onEnd.join(rsp.stream.onEnd).respond {
        case Return(_) => streamTimeMs.add(reqT().inMillis)
        case Throw(_) => streamFailures.incr()
      }
    }

    rspF
  }

}
