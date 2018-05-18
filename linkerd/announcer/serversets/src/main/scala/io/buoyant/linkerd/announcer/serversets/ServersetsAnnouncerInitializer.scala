package io.buoyant.linkerd.announcer.serversets

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import com.twitter.finagle.zookeeper.buoyant.ZkAnnouncer
import io.buoyant.config.types.HostAndPort
import io.buoyant.linkerd.{Announcer, AnnouncerConfig, AnnouncerInitializer}

class ServersetsAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass = classOf[ServersetsConfig]
  override def configId = "io.l5d.serversets"
}

case class ServersetsConfig(zkAddrs: Seq[HostAndPort], pathPrefix: Option[Path]) extends AnnouncerConfig {

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.serversets")

  override def mk(params: Stack.Params): Announcer = new ZkAnnouncer(zkAddrs, pathPrefix.getOrElse(Path.read("/discovery")))

}
