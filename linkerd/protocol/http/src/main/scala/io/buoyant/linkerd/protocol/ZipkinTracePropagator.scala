package io.buoyant.linkerd.protocol

import com.twitter.finagle.Stack
import com.twitter.finagle.http.{HeaderMap, Request}
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}
import io.buoyant.linkerd.protocol.http.ZipkinTracePropagator
import io.buoyant.router.HttpInstances._

class ZipkinTracePropagatorInitializer extends TracePropagatorInitializer {
  override val configClass = classOf[ZipkinTracePropagatorConfig]
  override val configId = "io.l5d.zipkin"
}

case class ZipkinTracePropagatorConfig() extends HttpTracePropagatorConfig {
  override def mk(params: Stack.Params): TracePropagator[Request] = new ZipkinTracePropagator[Request, HeaderMap]
}
