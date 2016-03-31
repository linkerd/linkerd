package io.l5d

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.tracing.{Record, TraceId, Tracer}
import com.twitter.finagle.zipkin.thrift.{Sampler, ZipkinTracer}
import io.buoyant.linkerd.{TracerConfig, TracerInitializer}

class ZipkinTracerInitializer extends TracerInitializer {
  val configClass = classOf[zipkin]
}

object ZipkinTracerInitializer extends ZipkinTracerInitializer

case class zipkin(
  host: Option[String],
  port: Option[Int],
  sampleRate: Option[Double]
) extends TracerConfig {

  @JsonIgnore
  override def newTracer(): Tracer = new Tracer {
    private[this] val underlying: Tracer =
      ZipkinTracer.mk(
        host = host.getOrElse("localhost"),
        port = port.getOrElse(9410),
        sampleRate = sampleRate.map(_.toFloat).getOrElse(Sampler.DefaultSampleRate)
      )

    def sampleTrace(t: TraceId) = underlying.sampleTrace(t)
    def record(r: Record) = underlying.record(r)
  }
}
