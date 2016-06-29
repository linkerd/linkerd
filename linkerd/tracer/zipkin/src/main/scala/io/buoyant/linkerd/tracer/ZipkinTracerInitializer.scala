package io.buoyant.linkerd.tracer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.stats.{ClientStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.thrift.{Protocols, ThriftClientFramedCodec}
import com.twitter.finagle.tracing.{Record, Trace, TraceId, Tracer}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.zipkin.thrift.{RawZipkinTracer, Sampler, ZipkinTracer}
import com.twitter.finagle.zipkin.thriftscala.Scribe
import io.buoyant.linkerd.{TracerConfig, TracerInitializer}
import java.net.InetSocketAddress

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
      val transport = ClientBuilder()
        .name("zipkin-tracer")
        .hosts(new InetSocketAddress(host.getOrElse("localhost"), port.getOrElse(9410)))
        .codec(ThriftClientFramedCodec())
        .reportTo(ClientStatsReceiver)
        .hostConnectionLimit(5)
        .hostConnectionMaxWaiters(250)
        // reduce timeouts because trace requests should be fast
        .timeout(100.millis)
        .daemon(true)
        // disable failure accrual so that we don't evict nodes when connections
        // are saturated
        .noFailureAccrual
        // disable fail fast since we often be sending to a load balancer
        .failFast(false)
        .build()

      val client = new Scribe.FinagledClient(
        new TracelessFilter andThen transport,
        Protocols.binaryFactory()
      )

      val rawTracer = RawZipkinTracer(
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
