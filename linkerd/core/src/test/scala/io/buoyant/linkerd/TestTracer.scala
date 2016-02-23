package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.tracing.{Record, TraceId, Tracer}
import io.buoyant.linkerd.config.Parser

class TestTracerInitializer extends TracerInitializer {
  val configClass = classOf[TestTracer]
}

object TestTracerInitializer extends TestTracerInitializer

class TestTracer extends TracerConfig {
  @JsonIgnore
  override def newTracer(): Tracer = new Tracer {
    def record(record: Record): Unit = {}
    def sampleTrace(traceId: TraceId): Option[Boolean] = Some(true)
  }
}
