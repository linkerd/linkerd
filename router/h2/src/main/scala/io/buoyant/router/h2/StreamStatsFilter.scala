package io.buoyant.router.h2

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.service.{H2ReqRep, H2StreamClassifier}
import com.twitter.finagle.buoyant.h2.{param => h2param, _}
import com.twitter.finagle.service.ResponseClass.Successful
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util._

object StreamStatsFilter {
  /**
  * Configures a [[StreamStatsFilter.module]] to track latency using the
  * given [[TimeUnit]].
  */
  case class Param(unit: TimeUnit) {
    def mk(): (Param, Stack.Param[Param]) = (this, Param.param)
  }

  object Param {
    implicit val param = Stack.Param(Param(TimeUnit.MILLISECONDS))
  }


  val role = Stack.Role("StreamStatsFilter")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module3[param.Stats, h2param.H2StreamClassifier, Param, ServiceFactory[Request, Response]] {
      override def role: Stack.Role = StreamStatsFilter.role
      override def description = "Record stats on h2 streams"
      override def make(
        statsP: param.Stats,
        classifierP: h2param.H2StreamClassifier,
        timeP: Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] = {
        val param.Stats(stats) = statsP
        val h2param.H2StreamClassifier(classifier) = classifierP
        val Param(timeUnit) = timeP
        new StreamStatsFilter(stats, classifier, timeUnit).andThen(next)
      }
    }

}

class StreamStatsFilter(
  statsReceiver: StatsReceiver,
  classifier: H2StreamClassifier,
  timeUnit: TimeUnit)
  extends SimpleFilter[Request, Response] {

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

    def failure(streamDuration: Duration): Unit = {
      duration.add(streamDuration.inUnit(timeUnit))
      successes.incr()
    }

    private[this] val frameBytes = stats.stat("data_bytes")

    def apply(
      startT: Stopwatch.Elapsed,
      onFinalFrame: Option[Try[Frame]] => Unit = { _ => }
    )(underlying: Stream): Stream = {
      val streamFrameBytes = new AtomicLong(0)
      val stream = if (underlying.isEmpty) {
        // if the stream is empty, we still need to call the response
        // classifier with `None`, so add that to the `onEnd` callback.
        underlying.onEnd.respond { _ => onFinalFrame(None) }
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
            failure(startT())
        }
      }
      stream.onEnd.respond { result =>
        frameBytes.add(streamFrameBytes.get())
        if (result.isReturn) success(startT())
        else failure(startT())
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
        reqLatency.add(reqT().inUnit(timeUnit))
        val stream = result match {
          case Return(rsp) =>
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
