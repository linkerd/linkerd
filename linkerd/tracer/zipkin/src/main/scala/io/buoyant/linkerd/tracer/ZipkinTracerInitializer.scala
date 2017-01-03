package io.buoyant.linkerd.tracer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.stats.{ClientStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.thrift.Protocols
import com.twitter.finagle.tracing.{Record, Trace, TraceId, Tracer}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.zipkin.core.Sampler
import com.twitter.finagle.zipkin.thrift.{ScribeRawZipkinTracer, ZipkinTracer}
import com.twitter.finagle.zipkin.thriftscala.Scribe
import io.buoyant.linkerd.{TracerConfig, TracerInitializer}

class ZipkinTracerInitializer extends TracerInitializer {
  val configClass = classOf[ZipkinConfig]
  override def configId = "io.l5d.zipkin"
}

object ZipkinTracerInitializer extends ZipkinTracerInitializer

case class ZipkinConfig(
  host: Option[String],
  port: Option[Int],
  sampleRate: Option[Double]
) extends TracerConfig {

  @JsonIgnore
  override def newTracer(): Tracer = new Tracer {
    private[this] val underlying: Tracer = {
      // Cribbed heavily from com.twitter.finagle.zipkin.thrift.RawZipkinTracer
      val transport = Thrift.client
        .withStatsReceiver(ClientStatsReceiver)
        .withSessionPool.maxSize(5)
        .configured(DefaultPool.Param.param.default.copy(maxWaiters = 250))
        // reduce timeouts because trace requests should be fast
        .withRequestTimeout(100.millis)
        .withSessionQualifier.noFailureAccrual
        .withSessionQualifier.noFailFast
        .newService(Name.bound(Address(host.getOrElse("localhost"), port.getOrElse(9410))), "zipkin-tracer")

      val client = new Scribe.FinagledClient(
        new TracelessFilter andThen transport,
        Protocols.binaryFactory()
      )

      val rawTracer = ScribeRawZipkinTracer(
        client,
        NullStatsReceiver,
        DefaultTimer.twitter
      )
      new ZipkinTracer(
        rawTracer,
        sampleRate.map(_.toFloat).getOrElse(Sampler.DefaultSampleRate)
      )
    }

    def sampleTrace(t: TraceId) = underlying.sampleTrace(t)
    def record(r: Record) = underlying.record(r)
  }
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
