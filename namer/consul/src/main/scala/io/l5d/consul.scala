package io.l5d.experimental

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.param.Label
import com.twitter.finagle.{Http, Path, Stack}
import io.buoyant.consul.{CatalogNamer, SetHostFilter, v1}
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.experimental.consul
 *   host: consul.site.biz
 *   port: 8600
 * </pre>
 */
class ConsulInitializer extends NamerInitializer {
  val configClass = classOf[consul]
}

object ConsulInitializer extends ConsulInitializer

case class consul(
  host: Option[String],
  port: Option[Port]
) extends NamerConfig {

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
    val service = Http.client
      .withParams(Http.client.params ++ params)
      .configured(Label("namer" + prefix))
      .filtered(new SetHostFilter(getHost, getPort))
      .newService(s"/$$/inet/$getHost/$getPort")

    def mkNs(ns: String) = v1.Api(service)
    new CatalogNamer(prefix, mkNs)
  }
}

