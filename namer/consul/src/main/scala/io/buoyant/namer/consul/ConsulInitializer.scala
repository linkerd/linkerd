package io.buoyant.namer.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.param.Label
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Filter, Http, Path, Stack}
import io.buoyant.config.types.Port
import io.buoyant.consul.{SetAuthTokenFilter, SetHostFilter, v1}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.consul
 *   experimental: true
 *   host: consul.site.biz
 *   port: 8600
 *   includeTag: true
 *   setHost: true
 *   token: some-consul-acl-token
 * </pre>
 */
class ConsulInitializer extends NamerInitializer {
  val configClass = classOf[ConsulConfig]
  override def configId = "io.l5d.consul"
}

object ConsulInitializer extends ConsulInitializer

case class ConsulConfig(
  host: Option[String],
  port: Option[Port],
  includeTag: Option[Boolean],
  token: Option[String] = None,
  setHost: Option[Boolean] = None
) extends NamerConfig {

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.consul")

  private[this] def getHost = host.getOrElse("localhost")
  private[this] def getPort = port match {
    case Some(p) => p.port
    case None => 8500
  }

  /**
   * Build a Namer backed by Consul.
   */
  @JsonIgnore
  def newNamer(params: Stack.Params): CatalogNamer = {
    val authFilter = token match {
      case Some(t) => new SetAuthTokenFilter(t)
      case None => Filter.identity[Request, Response]
    }
    val filters = new SetHostFilter(getHost, getPort) andThen authFilter

    val service = Http.client
      .withParams(Http.client.params ++ params)
      .configured(Label("namer" + prefix))
      .withTracer(NullTracer)
      .filtered(filters)
      .newService(s"/$$/inet/$getHost/$getPort")

    new CatalogNamer(
      prefix,
      v1.CatalogApi(service),
      v1.AgentApi(service),
      includeTag = includeTag.getOrElse(false),
      setHost = setHost.getOrElse(false)
    )
  }
}
