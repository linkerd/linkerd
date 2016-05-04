package io.l5d.zk

import com.twitter.finagle.Announcer
import com.twitter.finagle.zookeeper.buoyant.ZkAnnouncer
import io.buoyant.linkerd.{AnnouncerInitializer, AnnouncerConfig}

class ZkAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass = classOf[ZkAnnouncerConfig]
  override def configId = "zk"
}

case class ZkAnnouncerConfig() extends AnnouncerConfig {
  override def mk(): Announcer = new ZkAnnouncer
}
