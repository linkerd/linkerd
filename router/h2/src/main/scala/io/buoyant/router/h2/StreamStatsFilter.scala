package io.buoyant.router.h2

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.twitter.finagle._
import com.twitter.finagle.buoyant.syntheticException
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.buoyant.h2.{param => h2param, _}
import com.twitter.finagle.service.ResponseClass.Successful
import com.twitter.finagle.stats.{ExceptionStatsHandler, StatsReceiver}
import com.twitter.util._

object StreamStatsFilter {
  val role = Stack.Role("StreamStatsFilter")

  /**
   * Configures a [[StreamStatsFilter.module]] to track latency using the
   * given TimeUnit.
   */
  case class Param(unit: TimeUnit) {
    def mk(): (Param, Stack.Param[Param]) = (this, Param.param)
  }

  object Param {
    implicit val param = Stack.Param(Param(TimeUnit.MILLISECONDS))
  }

  /**
   * Creates a com.twitter.finagle.Stackable [[StreamStatsFilter]].
   */
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module4[param.Stats, h2param.H2Classifier, param.ExceptionStatsHandler, Param, ServiceFactory[Request, Response]] {
      override val role: Stack.Role = StreamStatsFilter.role
      override val description = "Record stats on h2 streams"
      override def make(
        statsP: param.Stats,
        classifierP: h2param.H2Classifier,
        handlerP: param.ExceptionStatsHandler,
        timeP: Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] = {
        val param.Stats(stats) = statsP
        val h2param.H2Classifier(classifier) = classifierP
        val param.ExceptionStatsHandler(handler) = handlerP
        val Param(timeUnit) = timeP
        new StreamStatsFilter(stats, classifier, handler, timeUnit).andThen(next)
      }
    }

  private[StreamStatsFilter] val SyntheticException =
    syntheticException

}

class StreamStatsFilter(
  statsReceiver: StatsReceiver,
  classifier: H2Classifier,
  exceptionStats: ExceptionStatsHandler,
  timeUnit: TimeUnit
) extends SimpleFilter[Request, Response] {

  private[this] val latencyStatSuffix: String =
    timeUnit match {
      case TimeUnit.NANOSECONDS => "ns"
      case TimeUnit.MICROSECONDS => "us"
      case TimeUnit.MILLISECONDS => "ms"
      case TimeUnit.SECONDS => "secs"
      case _ => timeUnit.toString.toLowerCase
    }

  class StreamStats(
    protected val stats: StatsReceiver,
    durationName: Option[String] = None
  ) {
    private[this] val duration =
      stats.stat(s"${durationName.getOrElse("stream_duration")}_$latencyStatSuffix")
    private[this] val successes = stats.counter("stream_success")
    private[this] val failures = stats.counter("stream_failures")

    def success(streamDuration: Duration): Unit = {
      duration.add(streamDuration.inUnit(timeUnit))
      successes.incr()
    }

    def failure(streamDuration: Duration, e: Throwable): Unit = {
      duration.add(streamDuration.inUnit(timeUnit))
      failures.incr()
      exceptionStats.record(stats, e)
    }

    private[this] val frameBytes = stats.stat("data_bytes")

    def apply(
      startT: Stopwatch.Elapsed,
      onFinalFrame: Option[Try[Frame]] => Unit = { _ => }
    )(underlying: Stream): Stream = {
      val streamFrameBytes = new AtomicLong(0)
      val stream = if (underlying.isEmpty) {
        // if the stream is empty, we still need to call the response
        // classifier with `None`.
        onFinalFrame(None)
        underlying
      } else {
        underlying.onFrame {
          case Return(frame) =>
            frame match {
              case data: Frame.Data =>
                val _ = streamFrameBytes.addAndGet(data.buf.length)
              case _ =>
            }
            if (frame.isEnd) onFinalFrame(Some(Return(frame)))
          case Throw(e) =>
            onFinalFrame(Some(Throw(e)))
            failure(startT(), e)
        }
      }
      stream.onEnd.respond { result =>
        frameBytes.add(streamFrameBytes.get())
        result match {
          case Return(_) => success(startT())
          case Throw(e) => failure(startT(), e)
        }
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

  private[this] val failed: Try[_] => Option[Throwable] = {
    case Throw(e) => Some(e); case _ => None
  }

  override def apply(req0: Request, service: Service[Request, Response]): Future[Response] = {
    reqCount.incr()
    val reqT = Stopwatch.start()
    val req1 = Request(req0.headers, reqStreamStats(reqT)(req0.stream))

    @inline def classify(rsp: Response)(frame: Option[Try[Frame]]): Unit =
      // Only classify if early classification wasn't applicable.
      if (!classifier.responseClassifier.isDefinedAt(H2ReqRep(req1, Return(rsp)))) {
        classifier.streamClassifier(H2ReqRepFrame(req1, Return((rsp, frame)))) match {
          case Successful(_) =>
            successes.incr()
          case _ =>
            val exception = frame.flatMap(failed).getOrElse(StreamStatsFilter.SyntheticException)
            exceptionStats.record(statsReceiver, exception)
        }
      }

    service(req1)
      .transform {
        case Return(response) =>
          val rspT = Stopwatch.start()
          val stream = rspStreamStats(rspT, classify(response))(response.stream)
          Future.value(Response(response.headers, stream))
        case Throw(e) =>
          Future.exception(e)
      }
      .respond { result =>
        reqLatency.add(reqT().inUnit(timeUnit))
        classifier.responseClassifier.lift(H2ReqRep(req1, result)) match {
          case Some(Successful(_)) =>
            successes.incr()
          case Some(_) =>
            val exception = failed(result).getOrElse(StreamStatsFilter.SyntheticException)
            exceptionStats.record(statsReceiver, exception)
          case None => ()
        }
        val stream = result match {
          case Return(rsp) =>
            req1.stream.onEnd.join(rsp.stream.onEnd)
          case Throw(_) => req1.stream.onEnd
        }
        val _ = stream.respond {
          case Return(_) => totalStreamStats.success(reqT())
          case Throw(e) => totalStreamStats.failure(reqT(), e)
        }
      }

  }

}
