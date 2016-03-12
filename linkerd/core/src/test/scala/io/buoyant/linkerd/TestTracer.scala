package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.tracing.{Record, TraceId, Tracer}
import io.buoyant.config.Parser

class TestTracerInitializer extends TracerInitializer {
  val configClass = classOf[TestTracerConfig]
  override val configId = "test"
}

object TestTracerInitializer extends TestTracerInitializer

class TestTracerConfig extends TracerConfig {
  @JsonIgnore
  override def newTracer(): Tracer = new TestTracer
}

class TestTracer extends Tracer {
  def record(record: Record): Unit = {}
  def sampleTrace(traceId: TraceId): Option[Boolean] = Some(true)
}
