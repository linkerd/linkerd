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
      override def role: Stack.Role = StreamStatsFilter.role
      override def description = "Record stats on h2 streams"
      override def make(
        statsP: param.Stats,
        classifierP: h2param.H2ResponseClassifier,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] = {
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

    def this(stats: StatsReceiver) = this(stats, new StreamStats(stats))

    def apply(startT: Stopwatch.Elapsed, classifyFrame: Try[Frame] => Unit = { _ => })
             (underlying: Stream): Stream = {
      // TODO: add a fold to `StreamProxy`, so we don't have to use a `var`?
      var streamFrameBytes: Int = 0
      // partially evaluate this w/ a reference to the start time
      val streamStatsT = streamStats(startT)(_)
      val stream =
        if (underlying.isEmpty) {
          // it would be easier to just put this in `stream.onEnd` and handle
          // both the empty-stream case and the end of a non-empty stream case
          // in the same block of code, but `join()`ing `rsp.stream.onEnd` with
          // the `req` stream's `.onEnd` future in `StreamStatsFilter.apply()`
          // somehow clobbers this callback, so it never triggers.
          //
          // i strongly suspect this is a bug in `Stream.onEnd`, since this
          // behaviour certainly doesn't *seem* right, to me. i'd like to fix
          // this at the source, but for now, this is a (somewhat inelegant)
          // workaround...
          //  - eliza, 7/20/2017
          frameBytes.add(0)
          streamStatsT(Return(()))
          underlying
        } else underlying.onFrame {
            case Return(frame) =>
              frame match {
                case data: Frame.Data => streamFrameBytes += data.buf.length
                case _ =>
              }
              if (frame.isEnd) {
                // end frames get special-cased since the `.respond {}`
                // callback we were placing on `stream.onEnd` gets
                // clobbered – see above comment
                frameBytes.add(streamFrameBytes)
                classifyFrame(Return(frame))
                streamStatsT(Return(frame))
              }
            case e @ Throw(_) =>
              classifyFrame(e)
              streamStatsT(e)
          }
      stream
    }

  }

  // total number of requests received
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

    @inline def classify(rsp: Try[Response])(frame: Try[Frame]): Unit =
      classifier(H2ReqRep(req1, rsp.map((_, frame)))) match {
        case Successful(_) => successes.incr()
        case _ => failures.incr()
      }

    service(req1)
      .transform {
        case Return(response) =>
          val rspT = Stopwatch.start()
          val stream = rspStreamStats(rspT, classify(Return(response)))(response.stream)
          Future.value(Response(response.headers, stream))
        case Throw(e) =>
          classify(Throw(e))(Throw(e))
          Future.exception(e)
      }
      .respond { result =>
        reqLatency.add(reqT().inMillis)
        val stream = result match {
          case Return(rsp) =>
            // this is the call i mentioned above that seems to be wiping out
            // the `.onEnd.respond { }` callback in `frameStats.apply()`...
            req1.stream.onEnd.join(rsp.stream.onEnd)
          case Throw(_) => req1.stream.onEnd
        }
        val _ = stream.respond(totalStreamStats(reqT))
      }

  }

}
