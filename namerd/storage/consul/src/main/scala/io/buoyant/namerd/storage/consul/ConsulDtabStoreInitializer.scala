package io.buoyant.namerd.storage.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Http, Path}
import io.buoyant.config.types.Port
import io.buoyant.consul.SetHostFilter
import io.buoyant.consul.v1.KvApiV1
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class ConsulConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path],
  recursive: Option[Boolean]
) extends DtabStoreConfig {
  import ConsulConfig._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def mkDtabStore: DtabStore = {
    val serviceHost = host.getOrElse(DefaultHost)
    val servicePort = port.getOrElse(DefaultPort).port

    val service = Http.client
      .withTracer(NullTracer)
      .filtered(new SetHostFilter(serviceHost, servicePort))
      .newService(s"/$$/inet/$serviceHost/$servicePort")
    new ConsulDtabStore(
      KvApiV1(service),
      pathPrefix.getOrElse(Path.read("/namerd/dtabs")),
      recursive.getOrElse(false)
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
