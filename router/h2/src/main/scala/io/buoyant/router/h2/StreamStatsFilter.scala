package io.buoyant.router.h2

import java.util.concurrent.atomic.AtomicLong
import com.twitter.finagle._
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


  class StreamStats(
    protected val stats: StatsReceiver,
    durationName: Option[String] = None
  ) {
    private[this] val durationMs =
      stats.stat(s"${durationName.getOrElse("stream_duration")}_ms")
    private[this] val successes = stats.counter("stream_success")
    private[this] val failures = stats.counter("stream_failures")

    def success(streamDuration: Duration): Unit = {
      durationMs.add(streamDuration.inMillis)
      successes.incr()
    }

    def failure(streamDuration: Duration): Unit = {
      durationMs.add(streamDuration.inMillis)
      successes.incr()
    }
    private[this] val frameBytes = stats.stat("data_frame", "total_bytes")


    def apply(
      startT: Stopwatch.Elapsed,
      classifyFrame: Option[Try[Frame]] => Unit = { _ => }
    )(underlying: Stream): Stream = {
      // TODO: add a fold to `StreamProxy`, so we don't have to use a `var`?
      val streamFrameBytes = new AtomicLong(0)
      // partially evaluate this w/ a reference to the start time
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
          classifyFrame(None)
          success(startT())
          underlying
        } else underlying.onFrame {
          case Return(frame) =>
            frame match {
              case data: Frame.Data => val _ = streamFrameBytes.addAndGet(data.buf.length)
              case _ =>
            }
            if (frame.isEnd) {
              // end frames get special-cased since the `.respond {}`
              // callback we were placing on `stream.onEnd` gets
              // clobbered – see above comment
              frameBytes.add(streamFrameBytes.get())
              classifyFrame(Some(Return(frame)))
              success(startT())
            }
          case Throw(e) =>
            classifyFrame(Some(Throw(e)))
            failure(startT())
        }
      stream
    }

  }

  // total number of requests received
  private[this] val reqCount = statsReceiver.counter("requests")
  // total latency from receipt of request to completion of response future
  private[this] val reqLatency = statsReceiver.stat("request_latency_ms")
  // overall successes stat from response classifier
  private[this] val successes = statsReceiver.counter("success")
  // overall failures stat from response classifier
  private[this] val failures = statsReceiver.counter("failures")

  private[this] val reqStreamStats =
    new StreamStats(statsReceiver.scope("request", "stream"))
  private[this] val rspStreamStats =
    new StreamStats(statsReceiver.scope("response", "stream"))
  private[this] val totalStreamStats =
    new StreamStats(
      statsReceiver.scope("stream"),
      durationName = Some("total_latency")
    )


  override def apply(req0: Request, service: Service[Request, Response]): Future[Response] = {
    reqCount.incr()
    val reqT = Stopwatch.start()
    val req1 = Request(req0.headers, reqStreamStats(reqT)(req0.stream))

    @inline def classify(rsp: Try[Response])(frame: Option[Try[Frame]]): Unit =
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
          classify(Throw(e))(None)
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
        val _ = stream.respond {
          case Return(_) => totalStreamStats.success(reqT())
          case Throw(_) => totalStreamStats.failure(reqT())
        }
      }

  }

}
