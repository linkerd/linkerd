package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.h2.{Headers, Request}
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}
import io.buoyant.linkerd.protocol.http.ZipkinTracePropagator
import io.buoyant.router.H2Instances._

class ZipkinTracePropagatorInitializer extends TracePropagatorInitializer {
  override val configClass = classOf[ZipkinTracePropagatorConfig]
  override val configId = "io.l5d.zipkin"
}

case class ZipkinTracePropagatorConfig() extends H2TracePropagatorConfig {
  override def mk(params: Stack.Params): TracePropagator[Request] = new ZipkinTracePropagator[Request, Headers]
}