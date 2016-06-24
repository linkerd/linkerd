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
      val transport = ClientBuilder()
        .name("zipkin-tracer")
        .hosts(new InetSocketAddress(host.getOrElse("localhost"), port.getOrElse(9410)))
        .codec(ThriftClientFramedCodec())
        .reportTo(ClientStatsReceiver)
        .hostConnectionLimit(5)
        // using an arbitrary, but bounded number of waiters to avoid memory leaks
        .hostConnectionMaxWaiters(250)
        // somewhat arbitrary, but bounded timeouts
        .timeout(1.second)
        .daemon(true)
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
