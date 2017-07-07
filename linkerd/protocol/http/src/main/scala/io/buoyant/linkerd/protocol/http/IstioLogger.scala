package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Address, Filter, Name, Path, Service, Stack}
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.{DefaultMixerHost, DefaultMixerPort, MixerClient}
import io.buoyant.linkerd.LoggerInitializer
import io.buoyant.linkerd.protocol.HttpLoggerConfig
import io.buoyant.router.context.DstBoundCtx

object IstioLogger {
  val unknown = "unknown"

  // expected DstBoundCtx.current:
  // Some((Path(%,io.l5d.k8s.daemonset,default,incoming,l5d,#,io.l5d.k8s.istio,reviews.default.svc.cluster.local,az:us-west::env:prod::version:v1,http),Path()))
  val pathLength = 10
  val pathServiceIndex = 7
  val pathLabelsIndex = 8
}

class IstioLogger(mixerClient: MixerClient, params: Stack.Params) extends Filter[Request, Response, Request, Response] {
  import IstioLogger._

  private[this] val log = Logger()

  def apply(req: Request, svc: Service[Request, Response]) = {
    val elapsed = Stopwatch.start()

    svc(req).respond { ret =>
      val duration = elapsed()

      val responseCode = ret.toOption.map(_.statusCode).getOrElse(Status.InternalServerError.code)

      // check for valid istio path
      val istioPath = DstBoundCtx.current.flatMap { bound =>
        bound.id match {
          case path: Path if (path.elems.length == pathLength) => Some(path)
          case _ => None
        }
      }

      val targetService = istioPath.map { path =>
        path.showElems(pathServiceIndex)
      }

      val version = istioPath match {
        case Some(path) =>
          path.showElems(pathLabelsIndex).split("::").find {
            e => e.startsWith("version:")
          }.map { label => label.split(":").last }
        case _ => None
      }

      // note that this should really come from the "app" label of the target pod.
      // in most cases this will be the same value, but eventually the correct solution
      // should be to get this directly from the label or the uid.
      val targetLabelApp = targetService.flatMap(_.split('.').headOption)

      val _ = mixerClient.report(
        responseCode,
        req.path,
        targetService.getOrElse(unknown), // target in prom (~reviews.default.svc.cluster.local)
        unknown, // source in prom (~productpage)
        targetLabelApp.getOrElse(unknown), // service in prom (~reviews)
        version.getOrElse(unknown), // version in prom (~v1)
        duration
      )
    }
  }
}

case class IstioLoggerConfig(
  mixerHost: Option[String],
  mixerPort: Option[Port]
) extends HttpLoggerConfig {

  @JsonIgnore
  override def role = Stack.Role("IstioLogger")
  @JsonIgnore
  override def description = "Logs telemetry data to Istio Mixer"
  @JsonIgnore
  override def parameters = Seq()

  @JsonIgnore
  private[this] val log = Logger.get("IstioLoggerConfig")

  @JsonIgnore
  private[http] val host = mixerHost.getOrElse(DefaultMixerHost)
  @JsonIgnore
  private[http] val port = mixerPort.map(_.port).getOrElse(DefaultMixerPort)
  log.info(s"connecting to Istio Mixer at $host:$port")

  @JsonIgnore
  private[this] val mixerDst = Name.bound(Address(host, port))

  @JsonIgnore
  private[this] val mixerService = H2.client
    .withParams(H2.client.params)
    .newService(mixerDst, "istioLogger")

  @JsonIgnore
  private[http] val client = new MixerClient(mixerService)

  @JsonIgnore
  def mk(params: Stack.Params): Filter[Request, Response, Request, Response] = {
    new IstioLogger(client, params)
  }
}

class IstioLoggerInitializer extends LoggerInitializer {
  val configClass = classOf[IstioLoggerConfig]
  override val configId = "io.l5d.k8s.istio"
}

object IstioLoggerInitializer extends IstioLoggerInitializer
