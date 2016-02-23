package io.l5d.experimental

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.param.Label
import io.buoyant.k8s.v1.Api
import io.buoyant.k8s.{AuthFilter, EndpointsNamer, SetHostFilter}
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.config.types.Port
import io.buoyant.linkerd.{NamerConfig, NamerInitializer}
import scala.io.Source

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.experimental.k8s
 *   host: k8s-master.site.biz
 *   port: 80
 *   tls: false
 *   authTokenFile: ../auth.token
 * </pre>
 */
class k8s extends NamerInitializer {
  val configClass = Parser.jClass[k8sConfig]
  val configId = "io.l5d.experimental.k8s"
}

object k8s extends k8s {
  def authTokenFilter(authTokenFile: String): Filter[Request, Response, Request, Response] = {
    val token = Source.fromFile(authTokenFile).mkString
    if (token.isEmpty)
      Filter.identity
    else
      new AuthFilter(token)
  }
}

case class k8sConfig(
  host: Option[String],
  port: Option[Port],
  tls: Option[Boolean],
  tlsWithoutValidation: Option[Boolean],
  authTokenFile: Option[String]
) extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.k8s")

  private[this] def getHost = host.getOrElse("kubernetes.default.cluster.local")
  private[this] def getPort = port match {
    case Some(p) => p.port
    case None => 443
  }
  private[this] def authFilter: Filter[Request, Response, Request, Response] = authTokenFile match {
    case Some(path) => k8s.authTokenFilter(path)
    case None => Filter.identity
  }
  /**
   * Construct a namer.
   */
  @JsonIgnore
  override def newNamer(): Namer = {
    val setHost = new SetHostFilter(getHost, getPort)

    val client = (tls, tlsWithoutValidation) match {
      case (Some(false), _) => Http.client
      case (_, Some(true)) => Http.client.withTlsWithoutValidation
      case _ => Http.client.withTls(setHost.host)
    }

    // namer path -- should we just support a `label`?
    val path = prefix.show
    val service = client
      .configured(Label("namer" + path))
      .filtered(setHost)
      .filtered(authFilter)
      .withStreaming(true)
      .newService(s"/$$/inet/$getHost/$getPort")

    def mkNs(ns: String) = Api(service).namespace(ns)
    new EndpointsNamer(prefix, mkNs)
  }
}
