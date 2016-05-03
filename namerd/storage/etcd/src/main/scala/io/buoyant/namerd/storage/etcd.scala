package io.buoyant.namerd.storage

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Http, Path}
import io.buoyant.config.types.Port
import io.buoyant.etcd.Key
import io.buoyant.namerd.storage.etcd.EtcdDtabStore
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class EtcdConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path]
) extends DtabStoreConfig {
  import EtcdConfig._
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
  override def configId = "io.buoyant.namerd.storage.experimental.etcd"
}

object EtcdDtabStoreInitializer extends EtcdDtabStoreInitializer
