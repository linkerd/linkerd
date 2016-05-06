package io.l5d.etcd

import com.twitter.finagle.Announcer
import io.buoyant.linkerd.{AnnouncerConfig, AnnouncerInitializer}

class EtcdAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass: Class[_] = classOf[EtcdAnnouncerConfig]
}

case class EtcdAnnouncerConfig() extends AnnouncerConfig {
  override def mk(): Announcer = new EtcdAnnouncer()
}
