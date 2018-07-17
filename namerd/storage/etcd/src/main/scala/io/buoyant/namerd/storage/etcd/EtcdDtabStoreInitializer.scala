package io.buoyant.namerd.storage.etcd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.{Http, Path}
import io.buoyant.config.types.Port
import io.buoyant.etcd.Key
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class EtcdConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path] = None,
  tls: Option[TlsClientConfig] = None
) extends DtabStoreConfig {
  import EtcdConfig._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def mkDtabStore(params: Stack.Params): DtabStore = {
    val tlsParams = tls match {
      case Some(tlsConfig) => tlsConfig.params
      case _ => Stack.Params.empty
    }
    new EtcdDtabStore(new Key(
      pathPrefix.getOrElse(Path.read("/namerd/dtabs")),

      Http.client
        .withParams(Http.client.params ++ tlsParams)
        .newService(s"${host getOrElse DefaultHost}:${(port getOrElse DefaultPort).port}")
    ))
  }
}

object EtcdConfig {
  val DefaultHost = "localhost"
  val DefaultPort = Port(2379)
}

class EtcdDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[EtcdConfig]
  override def configId = "io.l5d.etcd"
}

object EtcdDtabStoreInitializer extends EtcdDtabStoreInitializer
