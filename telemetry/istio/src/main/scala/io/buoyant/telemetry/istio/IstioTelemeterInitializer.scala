package io.buoyant.telemetry.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Address, Name, Stack}
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import io.buoyant.telemetry.{TelemeterConfig, TelemeterInitializer}

class IstioTelemeterInitializer extends TelemeterInitializer {
  type Config = IstioConfig
  val configClass = classOf[IstioConfig]
  override val configId = "io.l5d.istio"
}

object IstioTelemeterInitializer extends IstioTelemeterInitializer

private[istio] object IstioConfig {
  val DefaultMixerHost = "istio-mixer.default.svc.cluster.local"
  val DefaultMixerPort = 9091
}

private[istio] case class IstioConfig(
  mixerHost: Option[String],
  mixerPort: Option[Int]
) extends TelemeterConfig {
  import IstioConfig._

  @JsonIgnore override val experimentalRequired = true

  @JsonIgnore private[this] val log = Logger.get()

  @JsonIgnore private[this] val istioMixerHost = mixerHost.getOrElse(DefaultMixerHost)
  @JsonIgnore private[this] val istioMixerPort = mixerPort.getOrElse(DefaultMixerPort)

  @JsonIgnore
  def mk(params: Stack.Params): IstioTelemeter = {
    log.info(s"connecting to Istio Mixer at $istioMixerHost:$istioMixerPort")

    val metricsDst = Name.bound(Address(istioMixerHost, istioMixerPort))

    val metricsService = H2.client
      .withParams(H2.client.params)
      .newService(metricsDst, "istioTelemeter")

    val client = MixerClient(metricsService)

    new IstioTelemeter(
      client,
      DefaultTimer
    )
  }
}
