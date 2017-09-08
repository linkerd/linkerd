package io.buoyant.linkerd.protocol.h2.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.util.Stopwatch
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.k8s.istio.{IstioConfigurator, IstioLoggerBase}
import io.buoyant.linkerd.LoggerInitializer
import io.buoyant.k8s.istio._
import io.buoyant.linkerd.protocol.h2.H2LoggerConfig

class IstioLogger(val mixerClient: MixerClient, params: Stack.Params) extends Filter[Request, Response, Request, Response] with IstioLoggerBase {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val istioRequest = H2IstioRequest(req)

    val elapsed = Stopwatch.start()

    svc(req).respond { ret =>

      val duration = elapsed()
      val istioResponse = H2IstioResponse(ret, duration)

      val _ = report(istioRequest, istioResponse, duration)
    }
  }
}

case class IstioLoggerConfig(
  mixerHost: Option[String] = Some(DefaultMixerHost),
  mixerPort: Option[Port] = Some(Port(DefaultMixerPort))
) extends H2LoggerConfig with IstioConfigurator {

  @JsonIgnore
  override def role = Stack.Role("IstioLogger")
  @JsonIgnore
  override def description = "Logs telemetry data to Istio Mixer"

  @JsonIgnore
  override def parameters = Seq()

  @JsonIgnore
  def mk(params: Stack.Params): Filter[Request, Response, Request, Response] = {
    new IstioLogger(mkMixerClient(mixerHost, mixerPort), params)
  }
}

class IstioLoggerInitializer extends LoggerInitializer {
  val configClass = classOf[IstioLoggerConfig]
  override val configId = "io.l5d.k8s.istio"
}

object IstioLoggerInitializer extends IstioLoggerInitializer
