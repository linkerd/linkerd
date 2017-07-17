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
    scopes: Seq[String] = Nil,
    durationName: Option[String] = None,
    successName: Option[String] = None) {

    private[this] val (durationMs, successes, failures) = {
      val scope = scopes.foldLeft(statsReceiver){ (scope, name) => scope.scope(name) }
      ( scope.stat(s"${durationName.getOrElse("stream_duration")}_ms")
      , scope.counter(s"${successName.getOrElse("stream")}_successes")
      , scope.counter(s"${successName.getOrElse("stream")}_failures"))
    }

    @inline def apply(startT: Stopwatch.Elapsed)(result: Try[_]): Unit = {
      durationMs.add(startT().inMillis)
      result match {
        case Return(_) => successes.incr()
        case Throw(_) => failures.incr()
      }
    }
  }

  // total number of requests received
  private[this] val reqCount = statsReceiver.counter("stream", "requests")

  private[this] val reqStreamStats = new StreamStats(Seq("stream", "request"))
  private[this] val rspStreamStats = new StreamStats(Seq("stream", "response"))
  private[this] val totalStreamStats = new StreamStats(Seq("stream"), Some("total_latency"))
  private[this] val rspFutureStats = new StreamStats(Seq("response"), Some("response_latency"), Some("response"))

  override def apply(req: Request, service: Service[Request, Response]): Future[Response] = {
    reqCount.incr()
    val reqT = Stopwatch.start()

    req.stream.onEnd.respond(reqStreamStats(reqT))

    service(req)
      .respond(rspFutureStats(reqT))
      .onSuccess { rsp =>
        val rspT = Stopwatch.start()
        rsp.stream.onEnd.respond(rspStreamStats(rspT))

        val _ = req.stream.onEnd
          .join(rsp.stream.onEnd)
          .respond(totalStreamStats(reqT))
      }
  }

}
