package io.buoyant.interpreter.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Http, Path, Stack, param}
import io.buoyant.config.types.Port
import io.buoyant.k8s.{SetHostFilter, SingleNsNamer}
import io.buoyant.k8s.v1.Api
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer, Paths}

class IstioInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[IstioInterpreterConfig]
  override val configId = "io.l5d.k8s.istio"
}

object IstioInterpreterInitializer extends IstioInterpreterInitializer

case class IstioInterpreterConfig(
  k8sApiserverHost: Option[String],
  k8sApiserverPort: Option[Port],
  envVar: Option[String],
  labelSelector: Option[String],
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port]
) extends InterpreterConfig {
  import io.buoyant.k8s.istio._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  val prefix: Path = Path.read("/io.l5d.k8s.istio")

  @JsonIgnore
  def nsName = sys.env.get(envVar.getOrElse("POD_NAMESPACE"))

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

    val namespace = nsName.getOrElse("default")
    val k8sHost = k8sApiserverHost.getOrElse("localhost")
    val k8sPort = k8sApiserverPort.map(_.port).getOrElse(8001)
    val setHost = new SetHostFilter(k8sHost, k8sPort)
    val client = Http.client.withParams(Http.client.params ++ params)
      .withTracer(NullTracer)
      .withStreaming(true)
      .filtered(setHost)

    def mkNs(ns: String) = {
      val label = param.Label(s"namer${prefix.show}/$ns")
      val dst = s"/$$/inet/$k8sHost/$k8sPort"
      Api(client.configured(label).newService(dst)).withNamespace(ns)
    }
    val k8sNamer = new SingleNsNamer(Paths.ConfiguredNamerPrefix ++ prefix, labelSelector, namespace, mkNs)

    IstioInterpreter(routeManager, istioNamer, k8sNamer)
  }
}
