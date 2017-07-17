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

  // total number of requests received
  private[this] val reqCount = statsReceiver.counter("stream", "requests")
  // time from request until request stream completes (successfully or not)
  private[this] val reqStreamTimeMs = statsReceiver.stat("stream", "request", "stream_duration_ms")
  // time from response until response stream completes (successfully or not)
  private[this] val rspStreamTimeMs = statsReceiver.stat("stream", "response", "stream_duration_ms")

  // number of response futures that succeed
  private[this] val rspSuccess = statsReceiver.counter("stream", "response", "response_success")
  // number of response futures that fail
  private[this] val rspFailures = statsReceiver.counter("stream", "response", "response_failures")
  // number of request streams that fail
  private[this] val reqStreamFailures = statsReceiver.counter("stream", "request", "stream_failures")
  // number of request streams that succeed
  private[this] val reqStreamSuccesses = statsReceiver.counter("stream", "request", "stream_success")
  // number of response streams that fail
  private[this] val rspStreamFailures = statsReceiver.counter("stream", "response", "stream_failures")
  // number of response futures that succeed
  private[this] val rspStreamSuccesses = statsReceiver.counter("stream", "response", "stream_success")
  // number of times any stream fails
  private[this] val streamFailures = statsReceiver.counter("stream", "failures")
  // number of times both streams succeed
  private[this] val streamSuccesses = statsReceiver.counter("stream", "success")


  // time from request until response future completes (successfully or not)
  private[this] val rspLatencyMs = statsReceiver.stat("stream", "response", "latency_ms")
  // time from request until both streams complete (successfully or not)
  private[this] val totalLatencyMs = statsReceiver.stat("stream", "total_latency_ms")

  override def apply(req: Request, service: Service[Request, Response]): Future[Response] = {
    reqCount.incr()
    val reqT = Stopwatch.start()

    req.stream.onEnd.respond { result =>
      reqStreamTimeMs.add(reqT().inMillis)
      result match {
        case Return(_) => reqStreamSuccesses.incr()
        case Throw(_) => reqStreamFailures.incr()
      }
    }

    service(req)
      .onSuccess { rsp =>
        rspLatencyMs.add(reqT().inMillis)
        rspSuccess.incr()

        val rspT = Stopwatch.start()
        rsp.stream.onEnd.respond { result =>
          rspStreamTimeMs.add(rspT().inMillis)
          result match {
            case Throw(_) => rspStreamFailures.incr()
            case Return(_) => rspStreamSuccesses.incr()
          }
        }

        val _ = req.stream.onEnd.join(rsp.stream.onEnd).respond { result =>
          totalLatencyMs.add(reqT().inMillis)
          result match {
            case Return(_) => streamSuccesses.incr()
            case Throw(_) => streamFailures.incr()
          }
        }
      }
      .onFailure { err =>
        rspLatencyMs.add(reqT().inMillis)
        rspFailures.incr()
      }
  }

}
