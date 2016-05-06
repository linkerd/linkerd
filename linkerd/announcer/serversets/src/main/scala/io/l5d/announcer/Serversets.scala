package io.l5d.announcer

import com.twitter.finagle.Announcer
import com.twitter.finagle.zookeeper.buoyant.ZkAnnouncer
import io.buoyant.linkerd.{AnnouncerInitializer, AnnouncerConfig}

class ServersetsAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass = classOf[Serversets]
}

case class Serversets(hosts: Seq[String], pathPrefix: Option[String]) extends AnnouncerConfig {
  override def mk(): Announcer = new ZkAnnouncer(hosts.mkString(","), pathPrefix.getOrElse("/discovery"))
}
