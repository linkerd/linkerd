package io.buoyant.interpreter.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.{Path, Stack, param}
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, IstioNamer, SdsClient}
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer, Paths}

class IstioInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[IstioInterpreterConfig]
  override val configId = "io.l5d.k8s.istio"
}

object IstioInterpreterInitializer extends IstioInterpreterInitializer

case class IstioInterpreterConfig(
  host: Option[String],
  port: Option[Port],
  pollIntervalMs: Option[Long]
) extends InterpreterConfig with ClientConfig {

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override val DefaultHost = "istio-manager.default.svc.cluster.local"
  @JsonIgnore
  override val DefaultPort = 8080

  @JsonIgnore
  val prefix: Path = Path.read("/io.l5d.k8s.istio")

  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  private[this] val DefaultPollInterval = 5.seconds
  @JsonIgnore
  private[this] val pollInterval = pollIntervalMs.map(_.millis).getOrElse(DefaultPollInterval)

  override protected def newInterpreter(params: Stack.Params): NameInterpreter = {
    val label = param.Label("namer/io.l5d.k8s.istio")
    val client = mkClient(params).configured(label).newService(dst)
    val sdsClient = new SdsClient(client)
    val istioNamer = new IstioNamer(sdsClient, Paths.ConfiguredNamerPrefix ++ prefix, pollInterval)
    val routeManager = /* RouteManager(client) */ ()
    IstioInterpreter(routeManager, istioNamer)
  }
}
