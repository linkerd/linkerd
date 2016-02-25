package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.tracing.{Record, TraceId, Tracer}
import io.buoyant.linkerd.config.Parser

class TestTracer extends TracerInitializer {
  val configClass = Parser.jClass[TestTracerConfig]
  val configId = "io.buoyant.linkerd.TestTracer"
}

object TestTracer extends TestTracer

class TestTracerConfig extends TracerConfig {
  @JsonIgnore
  override def newTracer(): Tracer = new Tracer {
    def record(record: Record): Unit = {}
    def sampleTrace(traceId: TraceId): Option[Boolean] = Some(true)
  }
}
