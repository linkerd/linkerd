package io.buoyant.namerd.storage

import com.fasterxml.jackson.annotation.JsonIgnore
import io.buoyant.config.types.Port
import io.buoyant.k8s.ClientConfig
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class K8sConfig(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String]
) extends DtabStoreConfig with ClientConfig {

  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  override def mkDtabStore: DtabStore = {
    val client = mkClient()

    new K8sDtabStore(client, dst, namespace.getOrElse(DefaultNamespace))
  }
}

class K8sDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[K8sConfig]
  override def configId = "io.l5d.k8s"
}
