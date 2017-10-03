package com.medallia.l5d.curatorsd.announcer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.medallia.servicediscovery.ServiceDiscoveryRegistrar.RegistrationFormat
import com.twitter.finagle.Path
import io.buoyant.linkerd.{Announcer, AnnouncerConfig, AnnouncerInitializer}

class CuratorSDAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass = classOf[CuratorSDConfig]
  override def configId = "com.medallia.curatorsd"
}

case class CuratorSDConfig(zkConnectStr: String, format: Option[String], backwardsCompatibility: Option[String]) extends AnnouncerConfig {

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/com.medallia.curatorsd")

  override def mk(): Announcer = new CuratorSDAnnouncer(
    zkConnectStr,
    format.map(RegistrationFormat.valueOf).getOrElse(RegistrationFormat.V2),
    backwardsCompatibility
  )
}

