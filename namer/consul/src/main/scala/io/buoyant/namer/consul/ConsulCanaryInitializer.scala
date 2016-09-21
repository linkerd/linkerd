package io.buoyant.namer.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.param
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Failure, Filter, Http, Namer, Path, Stack}
import com.twitter.util.Monitor
import io.buoyant.config.types.Port
import io.buoyant.consul.v1.ConsistencyMode
import io.buoyant.consul.{SetAuthTokenFilter, SetHostFilter, v1}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

/**
  * Supports namer configurations in the form:
  *
  * <pre>
  * namers:
  * - kind: io.l5d.consulcanary
  *   experimental: true
  *   host: consul.site.biz
  *   port: 8600
  *   useHealthCheck: false
  *   setHost: true
  *   token: some-consul-acl-token
  * </pre>
  */
class ConsulCanaryInitializer extends NamerInitializer {
  val configClass = classOf[ConsulCanaryConfig]
  override def configId = "io.l5d.consulcanary"
}

object ConsulCanaryInitializer extends ConsulCanaryInitializer

case class ConsulCanaryConfig(
                         host: Option[String],
                         port: Option[Port],
                         useHealthCheck: Option[Boolean],
                         token: Option[String] = None,
                         setHost: Option[Boolean] = None,
                         consistencyMode: Option[ConsistencyMode] = None
                       ) extends NamerConfig {

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.consulcanary")

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
    val authFilter = token match {
      case Some(t) => new SetAuthTokenFilter(t)
      case None => Filter.identity[Request, Response]
    }
    val filters = new SetHostFilter(getHost, getPort) andThen authFilter
    val interruptionMonitor = Monitor.mk {
      case e: Failure if e.isFlagged(Failure.Interrupted) => true
    }

    val service = Http.client
      .withParams(Http.client.params ++ params)
      .withLabel(prefix.show.stripPrefix("/"))
      .withMonitor(interruptionMonitor)
      .withTracer(NullTracer)
      .filtered(filters)
      .newService(s"/$$/inet/$getHost/$getPort")

    val consul = useHealthCheck match {
      case Some(true) => v1.HealthApi(service)
      case _ => v1.CatalogApi(service)
    }
    val agent = v1.AgentApi(service)

    val stats = params[param.Stats].statsReceiver.scope(prefix.show.stripPrefix("/"))

    ConsulCanaryNamer.tagged(
      prefix, consul, agent, setHost.getOrElse(false), consistencyMode, stats
    )

  }
}
