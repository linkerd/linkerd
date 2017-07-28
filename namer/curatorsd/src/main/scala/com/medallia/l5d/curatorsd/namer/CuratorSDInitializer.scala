package com.medallia.l5d.curatorsd.namer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class CuratorSDInitializer extends NamerInitializer {
  val configClass = classOf[CuratorSDConfig]
  override def configId = "com.medallia.curatorsd"
}

object CuratorSDInitializer extends CuratorSDInitializer

case class CuratorSDConfig(zkConnectStr: String) extends NamerConfig {

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/com.medallia.curatorsd")

  /**
   * Construct a namer.
   */
  def newNamer(params: Stack.Params) = new CuratorSDNamer(zkConnectStr)
}
