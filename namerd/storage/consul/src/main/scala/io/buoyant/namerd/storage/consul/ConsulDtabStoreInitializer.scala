package io.buoyant.namerd.storage.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Http, Path}
import io.buoyant.config.types.Port
import io.buoyant.consul.utils.RichConsulClient
import io.buoyant.consul.v1.{ConsistencyMode, KvApi}
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class ConsulConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path],
  token: Option[String] = None,
  datacenter: Option[String] = None,
  readConsistencyMode: Option[ConsistencyMode] = None,
  writeConsistencyMode: Option[ConsistencyMode] = None,
  failFast: Option[Boolean] = None
) extends DtabStoreConfig {
  import ConsulConfig._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def mkDtabStore: DtabStore = {
    val serviceHost = host.getOrElse(DefaultHost)
    val servicePort = port.getOrElse(DefaultPort).port

    val service = Http.client
      .interceptInterrupts
      .failFast(failFast)
      .setAuthToken(token)
      .ensureHost(host, port)
      .withTracer(NullTracer)
      .newService(s"/$$/inet/$serviceHost/$servicePort")
    new ConsulDtabStore(
      KvApi(service),
      pathPrefix.getOrElse(Path.read("/namerd/dtabs")),
      datacenter = datacenter,
      readConsistency = readConsistencyMode,
      writeConsistency = writeConsistencyMode
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
