package io.buoyant.router.h2

import com.twitter.finagle.{Status => _, _}
import com.twitter.finagle.buoyant.h2.{param => h2param, _}
import com.twitter.finagle.service.ResponseClass.Successful
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util._

object StreamStatsFilter {
  val role = Stack.Role("StreamStatsFilter")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module2[param.Stats, h2param.H2ResponseClassifier, ServiceFactory[Request, Response]] {
      override def role = StreamStatsFilter.role
      override def description = "Record stats on h2 streams"
      override def make(
        statsP: param.Stats,
        classifierP: h2param.H2ResponseClassifier,
        next: ServiceFactory[Request, Response]
      ) = {
        val param.Stats(stats) = statsP
        val h2param.H2ResponseClassifier(classifier) = classifierP
        new StreamStatsFilter(stats, classifier).andThen(next)
      }
    }
}

class StreamStatsFilter(statsReceiver: StatsReceiver, classifier: H2ResponseClassifier)
  extends SimpleFilter[Request, Response] {

  // the `StreamStats` (and the related `FrameStats`) class is really just
  // a "stateful function" – essentially a closure, but closing over state
  // (the actual stat values) defined within its own scope. this is kind of
  // a FP take on object orientation, isn't scala cool?
  class StreamStats(protected val stats: StatsReceiver, durationName: Option[String] = None) {
    private[this] val durationMs =
      stats.stat(s"${durationName.getOrElse("stream_duration")}_ms")
    private[this] val successes = stats.counter("stream_success")
    private[this] val failures = stats.counter("stream_failures")

    @inline def apply(startT: Stopwatch.Elapsed)(result: Try[_]): Unit = {
      durationMs.add(startT().inMillis)
      result match {
        case Return(_) => successes.incr()
        case Throw(_) => failures.incr()
      }
    }

  }

  class FrameStats(protected val stats: StatsReceiver,
                   protected val streamStats: StreamStats) {

    private[this] val frameBytes = stats.stat("data_frame", "total_bytes")
    private[this] val frameCount = stats.stat("data_frame", "count")

    def this(stats: StatsReceiver) = this(stats, new StreamStats(stats))

    @inline def apply(startT: Stopwatch.Elapsed)(underlyingStream: Stream): Stream = {
      var streamFrameCount: Int = 0
      var streamFrameBytes: Int = 0
      val stream = underlyingStream.onFrame {
        case Return(frame: Frame.Data) =>
          streamFrameBytes += frame.buf.length
          streamFrameCount += 1
        case _ =>
      }
      val _ = stream.onEnd.respond {
        frameCount.add(streamFrameCount)
        frameBytes.add(streamFrameBytes)
        streamStats(startT)(_)
      }
      stream
    }

  }

  //   total number of requests received
  private[this] val reqCount = statsReceiver.counter("requests")

  private[this] val reqStreamStats =
    new FrameStats(statsReceiver.scope("request", "stream"))
  private[this] val rspStreamStats =
    new FrameStats(statsReceiver.scope("response", "stream"))
  private[this] val totalStreamStats =
    new StreamStats(
      statsReceiver.scope("stream"),
      durationName = Some("total_latency")
    )

  private[this] val reqLatency = statsReceiver.stat("request_latency_ms")

  // overall successes stat from response classifier
  private[this] val successes = statsReceiver.counter("success")
  // overall failures stat from response classifier
  private[this] val failures = statsReceiver.counter("failures")



  override def apply(req0: Request, service: Service[Request, Response]): Future[Response] = {
    reqCount.incr()
    val reqT = Stopwatch.start()
    val req1 = Request(req0.headers, reqStreamStats(reqT)(req0.stream))

    @inline def _classify(rsp: Try[(Response, Try[Frame])]): Unit =
      classifier(H2ReqRep(req1, rsp)) match {
        case Successful(_) => successes.incr()
        case _ => failures.incr()
      }

    service(req1)
      .transform {
        case Return(rsp0) =>
          val rspT = Stopwatch.start()
          val stream = rsp0.stream.onFrame {
            case Return(frame) if frame.isEnd =>
              _classify(Return((rsp0, Return(frame))))
            case e@Throw(_) => _classify(Return((rsp0, e)))
            case _ =>
          }
          Future.value(Response(rsp0.headers, rspStreamStats(rspT)(stream)))
        case Throw(e) =>
          _classify(Throw(e))
          Future.exception(e)
      }
      .respond { result =>
        reqLatency.add(reqT().inMillis)
        val stream = result match {
          case Return(rsp) =>
            req1.stream.onEnd.join(rsp.stream.onEnd)
          case Throw(_) => req1.stream.onEnd
        }
        val _ = stream.respond(totalStreamStats(reqT))
      }

  }

}
