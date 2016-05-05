package io.l5d.zk

import com.twitter.finagle.Announcer
import com.twitter.finagle.zookeeper.buoyant.ZkAnnouncer
import io.buoyant.linkerd.{AnnouncerInitializer, AnnouncerConfig}

class ServersetsAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass = classOf[ServersetsAnnouncerConfig]
  override def configId = "serversets"
}

case class ServersetsAnnouncerConfig(hosts: Seq[String], pathPrefix: Option[String]) extends AnnouncerConfig {
  override def mk(): Announcer = new ZkAnnouncer(hosts.mkString(","), pathPrefix.getOrElse("/discovery"))
}
