package io.buoyant.telemetry

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.stats.{ClientStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.thrift.Protocols
import com.twitter.finagle.tracing._
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.zipkin.core.Sampler
import com.twitter.finagle.zipkin.thrift.{ScribeRawZipkinTracer, ZipkinTracer}
import com.twitter.finagle.zipkin.thriftscala.Scribe

class ZipkinInitializer extends TelemeterInitializer {
  type Config = ZipkinConfig
  val configClass = classOf[ZipkinConfig]
  override val configId = "io.l5d.zipkin"
}

case class ZipkinConfig(
  host: Option[String],
  port: Option[Int],
  @JsonDeserialize(contentAs = classOf[java.lang.Double]) sampleRate: Option[Double]
) extends TelemeterConfig {

  private[this] val tracer: Tracer = new Tracer {
    val underlying: Tracer = {
      // Cribbed heavily from com.twitter.finagle.zipkin.thrift.RawZipkinTracer
      val transport = Thrift.client
        .withStatsReceiver(ClientStatsReceiver)
        .withSessionPool.maxSize(5)
        .configured(DefaultPool.Param.param.default.copy(maxWaiters = 250))
        // reduce timeouts because trace requests should be fast
        .withRequestTimeout(100.millis)
        // disable failure accrual so that we don't evict nodes when connections
        // are saturated
        .withSessionQualifier.noFailureAccrual
        // disable fail fast since we often be sending to a load balancer
        .withSessionQualifier.noFailFast
        .withTracer(NullTracer)
        .newService(Name.bound(Address(host.getOrElse("localhost"), port.getOrElse(9410))), "zipkin-tracer")

      val client = new Scribe.FinagledClient(
        new TracelessFilter andThen transport,
        Protocols.binaryFactory()
      )

      val rawTracer = ScribeRawZipkinTracer(
        client,
        NullStatsReceiver,
        DefaultTimer
      )
      new ZipkinTracer(
        rawTracer,
        sampleRate.map(_.toFloat).getOrElse(Sampler.DefaultSampleRate)
      )
    }

    def sampleTrace(t: TraceId) = underlying.sampleTrace(t)
    def record(r: Record) = underlying.record(r)
  }

  def mk(params: Stack.Params): ZipkinTelemeter = new ZipkinTelemeter(tracer)
}

class ZipkinTelemeter(underlying: Tracer) extends Telemeter {
  val stats = NullStatsReceiver
  lazy val tracer = underlying
  def run() = Telemeter.nopRun
}

/**
 * _Copied from Finagle's RawZipkinTracer.scala_
 *
 * Makes sure we don't trace the Scribe logging.
 */
private class TracelessFilter[Req, Rep] extends SimpleFilter[Req, Rep] {
  def apply(request: Req, service: Service[Req, Rep]) = {
    Trace.letClear {
      service(request)
    }
  }
}
