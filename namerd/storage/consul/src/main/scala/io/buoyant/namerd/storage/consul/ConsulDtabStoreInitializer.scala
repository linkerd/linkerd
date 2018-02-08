package io.buoyant.namerd.storage.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Http, Path}
import io.buoyant.config.types.Port
import io.buoyant.consul.utils.RichConsulClient
import io.buoyant.consul.v1.{ConsistencyMode, KvApi}
import io.buoyant.namer.BackoffConfig
import com.twitter.conversions.time._
import com.twitter.finagle.Stack
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class ConsulConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path],
  token: Option[String] = None,
  datacenter: Option[String] = None,
  readConsistencyMode: Option[ConsistencyMode] = None,
  writeConsistencyMode: Option[ConsistencyMode] = None,
  failFast: Option[Boolean] = None,
  backoff: Option[BackoffConfig] = None
) extends DtabStoreConfig {
  import ConsulConfig._

  @JsonIgnore
  override def mkDtabStore(params: Stack.Params): DtabStore = {
    val serviceHost = host.getOrElse(DefaultHost)
    val servicePort = port.getOrElse(DefaultPort).port
    val backoffs = backoff.map(_.mk).getOrElse(DefaultBackoff)

    val service = Http.client
      .interceptInterrupts
      .failFast(failFast)
      .setAuthToken(token)
      .ensureHost(host, port)
      .withTracer(NullTracer)
      .newService(s"/$$/inet/$serviceHost/$servicePort")
    new ConsulDtabStore(
      KvApi(service, backoffs),
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
  val DefaultBackoff = Backoff.decorrelatedJittered(1.millis, 1.minute)
}

class ConsulDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[ConsulConfig]
  override def configId = "io.l5d.consul"
}

object ConsulDtabStoreInitializer extends ConsulDtabStoreInitializer
