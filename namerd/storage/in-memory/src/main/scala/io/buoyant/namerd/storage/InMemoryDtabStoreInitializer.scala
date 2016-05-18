package io.buoyant.namerd.storage

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Dtab
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

class InMemoryDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[InMemoryConfig]
  override def configId = "io.l5d.inMemory"
}

case class InMemoryConfig(namespaces: Option[Map[String, Dtab]]) extends DtabStoreConfig {
  @JsonIgnore
  override def mkDtabStore: DtabStore = new InMemoryDtabStore(namespaces.getOrElse(Map.empty))
}
