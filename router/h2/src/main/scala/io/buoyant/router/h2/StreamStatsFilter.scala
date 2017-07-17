package io.buoyant.router.h2

import com.twitter.finagle.{Status => _, _}
import com.twitter.finagle.buoyant.h2.{Request, Response, Stream}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util._

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

  class StreamStats(
    stats: StatsReceiver,
    durationName: Option[String] = None,
    successName: Option[String] = None
  )(scopes: String*) {

    private[this] val (durationMs, successes, failures) = {
      val scope = scopes.foldLeft(statsReceiver) { (scope, name) => scope.scope(name) }
      (scope.stat(s"${durationName.getOrElse("stream_duration")}_ms"),
       scope.counter(s"${successName.getOrElse("stream_")}successes"),
       scope.counter(s"${successName.getOrElse("stream_")}failures"))
    }

    @inline def apply(startT: Stopwatch.Elapsed)(result: Try[_]): Unit = {
      durationMs.add(startT().inMillis)
      result match {
        case Return(_) => successes.incr()
        case Throw(_) => failures.incr()
      }
    }
  }

  //   total number of requests received
  private[this] val reqCount = statsReceiver.counter("requests")

  private[this] val reqStreamStats = new StreamStats(statsReceiver)("stream", "request")
  private[this] val rspStreamStats = new StreamStats(statsReceiver)("stream", "response")
  private[this] val totalStreamStats = new StreamStats(statsReceiver, Some("total_latency"))("stream")
  private[this] val rspFutureStats = new StreamStats(statsReceiver, Some("response_latency"), Some(""))("response")

  override def apply(req: Request, service: Service[Request, Response]): Future[Response] = {
    reqCount.incr()
    val reqT = Stopwatch.start()

    req.stream.onEnd.respond(reqStreamStats(reqT))

    service(req)
      .respond { result =>
        rspFutureStats(reqT)(result)
        val _ = (result match {
          case Return(rsp) => req.stream.onEnd.join(rsp.stream.onEnd)
          case Throw(_) => req.stream.onEnd
        }).respond(totalStreamStats(reqT))
      }
      .onSuccess { rsp =>
        val rspT = Stopwatch.start()
        val _ = rsp.stream.onEnd.respond(rspStreamStats(rspT))
      }


  }

}
