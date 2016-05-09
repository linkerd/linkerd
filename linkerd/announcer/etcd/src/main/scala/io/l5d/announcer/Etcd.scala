package io.l5d.announcer

import com.twitter.conversions.time._
import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Http, Announcer}
import com.twitter.util.Duration
import io.buoyant.linkerd.{AnnouncerConfig, AnnouncerInitializer}

class EtcdAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass: Class[_] = classOf[Etcd]
}

case class Etcd(
  host: Option[String],
  port: Option[Int],
  pathPrefix: Option[String],
  ttl: Option[Duration],
  refresh: Option[Duration]
) extends AnnouncerConfig {

  @JsonIgnore
  private[this] val getHost = host.getOrElse("localhost")
  @JsonIgnore
  private[this] val getPort = port.getOrElse(2379)

  override def mk(): Announcer = new EtcdAnnouncer(
    Http.newService(s"/$$/inet/$getHost/$getPort"),
    pathPrefix.getOrElse("/discovery"),
    ttl.getOrElse(2.minutes),
    refresh.getOrElse(1.minute)
  )(DefaultTimer.twitter)
}
