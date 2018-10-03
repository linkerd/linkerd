package io.buoyant.namer.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.TimeConversions._
import io.buoyant.config.types.Port
import io.buoyant.consul.utils.RichConsulClient
import io.buoyant.consul.v1
import io.buoyant.consul.v1.{ConsistencyMode, HealthStatus}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.consul
 *   host: consul.site.biz
 *   port: 8600
 *   includeTag: true
 *   useHealthCheck: true
 *   healthStatuses:
 *   - passing
 *   setHost: true
 *   token: some-consul-acl-token
 *   consistencyMode: default
 *   failFast: false
 *   preferServiceAddress: true
 *   weights:
 *   - tag: primary
 *     weight: 100
 *   tls:
 *     disableValidation: false
 *     commonName: consul.io
 *     trustCertsBundle: /certificates/cacert.pem
 *     clientAuth:
 *       certPath: /certificates/cert.pem
 *       keyPath: /certificates/key.pem
 * </pre>
 */
class ConsulInitializer extends NamerInitializer {
  val configClass = classOf[ConsulConfig]
  override def configId = "io.l5d.consul"
}

object ConsulInitializer extends ConsulInitializer

case class TagWeight(tag: String, weight: Double)

case class ConsulConfig(
  host: Option[String],
  port: Option[Port],
  includeTag: Option[Boolean],
  useHealthCheck: Option[Boolean],
  healthStatuses: Option[Set[HealthStatus.Value]] = None,
  token: Option[String] = None,
  setHost: Option[Boolean] = None,
  consistencyMode: Option[ConsistencyMode] = None,
  failFast: Option[Boolean] = None,
  preferServiceAddress: Option[Boolean] = None,
  weights: Option[Seq[TagWeight]] = None,
  tls: Option[TlsClientConfig] = None
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
  def newNamer(params: Stack.Params): Namer = {

    // Request timeout used to make sure long-polling requests are never stale.
    val DefaultRequestTimeout = 10.minutes
    val tlsParams = tls.map(_.params).getOrElse(Stack.Params.empty)

    val service = Http.client
      .withParams(Http.client.params ++ tlsParams ++ params)
      .withLabel("client")
      .interceptInterrupts
      .failFast(failFast)
      .setAuthToken(token)
      .ensureHost(host, port)
      .withTracer(NullTracer)
      .withRequestTimeout(DefaultRequestTimeout)
      .newService(s"/$$/inet/$getHost/$getPort")

    val consul = (useHealthCheck, healthStatuses) match {
      case (Some(true), Some(status)) => v1.HealthApi(service, status)
      case (Some(true), _) => v1.HealthApi(service, Set(HealthStatus.Passing))
      case _ => v1.CatalogApi(service)
    }
    val agent = v1.AgentApi(service)

    val tagWeights: Map[String, Double] = weights match {
      case Some(ws) => ws.map(tw => tw.tag -> tw.weight).toMap
      case None => Map.empty
    }

    val stats = params[param.Stats].statsReceiver.scope(prefix.show.stripPrefix("/"))

    includeTag match {
      case Some(true) =>
        ConsulNamer.tagged(
          prefix, consul, agent, setHost.getOrElse(false), consistencyMode, preferServiceAddress, tagWeights, stats
        )
      case _ =>
        ConsulNamer.untagged(
          prefix, consul, agent, setHost.getOrElse(false), consistencyMode, preferServiceAddress, tagWeights, stats
        )
    }
  }
}
