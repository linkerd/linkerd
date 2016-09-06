package io.buoyant.namerd.storage.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Failure, Filter, Http, Path}
import com.twitter.util.Monitor
import io.buoyant.config.types.Port
import io.buoyant.consul.v1.KvApi
import io.buoyant.consul.{SetAuthTokenFilter, SetHostFilter}
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class ConsulConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path],
  token: Option[String] = None,
  datacenter: Option[String] = None
) extends DtabStoreConfig {
  import ConsulConfig._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def mkDtabStore: DtabStore = {
    val serviceHost = host.getOrElse(DefaultHost)
    val servicePort = port.getOrElse(DefaultPort).port

    val authFilter = token match {
      case Some(t) => new SetAuthTokenFilter(t)
      case None => Filter.identity[Request, Response]
    }
    val filters = new SetHostFilter(serviceHost, servicePort) andThen authFilter
    val interruptionMonitor = Monitor.mk {
      case e: Failure if e.isFlagged(Failure.Interrupted) => true
    }

    val service = Http.client
      .withMonitor(interruptionMonitor)
      .withTracer(NullTracer)
      .filtered(filters)
      .newService(s"/$$/inet/$serviceHost/$servicePort")
    new ConsulDtabStore(
      KvApi(service),
      pathPrefix.getOrElse(Path.read("/namerd/dtabs")),
      datacenter = datacenter
    )
  }
}

object ConsulConfig {
  val DefaultHost = "localhost"
  val DefaultPort = Port(8500)
}

class ConsulDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[ConsulConfig]
  override def configId = "io.l5d.consul"
}

object ConsulDtabStoreInitializer extends ConsulDtabStoreInitializer
