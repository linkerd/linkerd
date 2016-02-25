package io.l5d.experimental

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
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

object k8s extends k8s

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

  private[this] def authFilter = authTokenFile match {
    case Some(path) =>
      val token = Source.fromFile(path).mkString
      if (token.nonEmpty) new AuthFilter(token)
      else Filter.identity[Request, Response]
    case None => Filter.identity[Request, Response]
  }

  /**
   * Construct a namer.
   */
  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = {
    val (host, port) = (getHost, getPort)
    val client = {
      val setHost = new SetHostFilter(host, port)
      val client = (tls, tlsWithoutValidation) match {
        case (Some(false), _) => Http.client
        case (_, Some(true)) => Http.client.withTlsWithoutValidation
        case _ => Http.client.withTls(setHost.host)
      }
      client.withParams(client.params ++ params)
        .withStreaming(true)
        .filtered(setHost)
        .filtered(authFilter)
    }
    val dst = s"/$$/inet/$host/$port"
    def mkNs(ns: String) = {
      val label = param.Label(s"namer${prefix.show}/$ns")
      Api(client.configured(label).newService(dst)).namespace(ns)
    }
    new EndpointsNamer(prefix, mkNs)
  }
}
