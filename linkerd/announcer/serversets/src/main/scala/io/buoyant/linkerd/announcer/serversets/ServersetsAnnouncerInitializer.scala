package io.buoyant.linkerd.announcer.serversets

import com.twitter.finagle.zookeeper.buoyant.ZkAnnouncer
import io.buoyant.config.types.HostAndPort
import io.buoyant.linkerd.{Announcer, AnnouncerConfig, AnnouncerInitializer}

class ServersetsAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass = classOf[ServersetsConfig]
  override def configId = "io.l5d.serversets"
}

case class ServersetsConfig(zkAddrs: Seq[HostAndPort], pathPrefix: Option[String]) extends AnnouncerConfig {
  override def mk(): Announcer = new ZkAnnouncer(zkAddrs, pathPrefix.getOrElse("/discovery"))
}

