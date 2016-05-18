package io.buoyant.namerd.storage.etcd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Http, Path}
import io.buoyant.config.types.Port
import io.buoyant.etcd.Key
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class EtcdConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path]
) extends DtabStoreConfig {
  import EtcdConfig._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def mkDtabStore: DtabStore = {
    new EtcdDtabStore(new Key(
      pathPrefix.getOrElse(Path.read("/namerd/dtabs")),
      Http.newService(s"${host getOrElse DefaultHost}:${port getOrElse DefaultPort}")
    ))
  }
}

object EtcdConfig {
  val DefaultHost = "localhost"
  val DefaultPort = 2379
}

class EtcdDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[EtcdConfig]
  override def configId = "io.l5d.etcd"
}

object EtcdDtabStoreInitializer extends EtcdDtabStoreInitializer
