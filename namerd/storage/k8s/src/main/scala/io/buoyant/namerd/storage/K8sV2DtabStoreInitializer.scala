package io.buoyant.namerd.storage

import com.fasterxml.jackson.annotation.JsonIgnore
import io.buoyant.config.types.Port
import io.buoyant.k8s.ClientConfig
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class K8sConfigV2(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String]
) extends DtabStoreConfig with ClientConfig {
  @JsonIgnore
  override def portNum: Option[Int] = port.map(_.port)

  @JsonIgnore
  override def mkDtabStore: DtabStore = {
    val client = mkClient()
    new CrdK8sDTabStore(client, dst, namespace.getOrElse(DefaultNamespace))
  }
}

class K8sV2DtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[K8sConfigV2]
  override def configId = "io.l5d.v2.k8s"
}

object K8sV2DtabStoreInitializer extends K8sV2DtabStoreInitializer
