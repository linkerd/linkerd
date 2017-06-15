package io.buoyant.interpreter.k8s

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonInclude}
import com.twitter.conversions.time._
import com.twitter.finagle.{Http, Path, Stack, param}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.tracing.NullTracer
import io.buoyant.config.types.Port
import io.buoyant.k8s._
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

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  val DefaultDiscoveryHost = "istio-manager.default.svc.cluster.local"
  @JsonIgnore
  val DefaultDiscoveryPort = 8080

  @JsonIgnore
  val DefaultApiserverHost = "istio-manager.default.svc.cluster.local"
  @JsonIgnore
  val DefaultApiserverPort = 8081

  @JsonIgnore
  val prefix: Path = Path.read("/io.l5d.k8s.istio")

  @JsonIgnore
  private[this] val DefaultPollInterval = 5.seconds
  @JsonIgnore
  private[this] val pollInterval = pollIntervalMs.map(_.millis).getOrElse(DefaultPollInterval)

  @JsonIgnore
  private[this] def discoveryClient(
    params: Stack.Params = Stack.Params.empty
  ) = {
    val host = discoveryHost.getOrElse(DefaultDiscoveryHost)
    val port = discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    val setHost = new SetHostFilter(host, port)
    Http.client.withParams(Http.client.params ++ params)
      .withTracer(NullTracer)
      .withStreaming(true)
      .filtered(setHost)
      .newService(s"/$$/inet/$host/$port", "namer/io.l5d.k8s.istio")
  }

  @JsonIgnore
  private[this] def apiserverClient(
    params: Stack.Params = Stack.Params.empty
  ) = {
    val host = discoveryHost.getOrElse(DefaultApiserverHost)
    val port = discoveryPort.map(_.port).getOrElse(DefaultApiserverPort)
    val setHost = new SetHostFilter(host, port)
    Http.client.withParams(Http.client.params ++ params)
      .withTracer(NullTracer)
      .withStreaming(true)
      .filtered(setHost)
      .newService(s"/$$/inet/$host/$port", "interpreter/io.l5d.k8s.istio")
  }

  override protected def newInterpreter(params: Stack.Params): NameInterpreter = {
    // TODO: Use some kind of client cache
    val pollInterval = pollIntervalMs.map(_.millis).getOrElse(DefaultPollInterval)
    val sdsClient = new SdsClient(discoveryClient(params))
    val istioNamer = new IstioNamer(sdsClient, Paths.ConfiguredNamerPrefix ++ prefix, pollInterval)
    val istioClient = new IstioPilotClient(apiserverClient(params))
    val routeManager = new RouteManager(istioClient, pollInterval)
    IstioInterpreter(routeManager, istioNamer)
  }
}
