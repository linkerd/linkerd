package io.buoyant.namerd.storage.experimental

import com.fasterxml.jackson.annotation.JsonIgnore
import io.buoyant.config.types.Port
import io.buoyant.k8s.ClientConfig
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

case class k8s(
  host: Option[String],
  port: Option[Port],
  tls: Option[Boolean],
  tlsWithoutValidation: Option[Boolean],
  authTokenFile: Option[String],
  namespace: Option[String]
) extends DtabStoreConfig with ClientConfig {
  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  override def mkDtabStore: DtabStore = {
    val client = mkClient()

    new K8sDtabStore(client, dst, namespace.getOrElse(ClientConfig.DefaultNamespace))
  }
}

class K8sDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[k8s]
}
