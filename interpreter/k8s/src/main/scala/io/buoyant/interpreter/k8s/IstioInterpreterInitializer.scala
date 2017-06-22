package io.buoyant.interpreter.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Path, Stack}
import io.buoyant.config.types.Port
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer, Paths}

class IstioInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[IstioInterpreterConfig]
  override val configId = "io.l5d.k8s.istio"
}

object IstioInterpreterInitializer extends IstioInterpreterInitializer

case class IstioInterpreterConfig(
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port],
  pollIntervalMs: Option[Long]
) extends InterpreterConfig {
  import io.buoyant.k8s.istio._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  val prefix: Path = Path.read("/io.l5d.k8s.istio")

  override protected def newInterpreter(params: Stack.Params): NameInterpreter = {
    val discoveryClient = DiscoveryClient(
      discoveryHost.getOrElse(DefaultDiscoveryHost),
      discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    )
    val istioNamer = new IstioNamer(discoveryClient, Paths.ConfiguredNamerPrefix ++ prefix)
    val routeManager = RouteCache.getManagerFor(
      apiserverHost.getOrElse(DefaultApiserverHost),
      apiserverPort.map(_.port).getOrElse(DefaultApiserverPort)
    )
    IstioInterpreter(routeManager, istioNamer)
  }
}
