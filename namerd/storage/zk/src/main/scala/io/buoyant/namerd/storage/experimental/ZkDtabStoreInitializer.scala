package io.buoyant.namerd.storage.experimental

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.serverset2.ZkDtabStore
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

class ZkDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[zk]
}

case class zk(
  hosts: Seq[String],
  pathPrefix: Option[String],
  sessionTimeoutMs: Option[Int]
) extends DtabStoreConfig {

  @JsonIgnore val sessionTimeout = sessionTimeoutMs.map(_.millis)

  @JsonIgnore
  override def mkDtabStore: DtabStore = new ZkDtabStore(
    hosts.mkString(","),
    pathPrefix.getOrElse("/dtabs"),
    sessionTimeout
  )
}
