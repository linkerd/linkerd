package io.buoyant.linkerd

import com.twitter.finagle.{Announcement, Announcer}
import com.twitter.util.Future
import java.net.InetSocketAddress

class TestAnnouncerInitializer extends AnnouncerInitializer {
  override def configClass: Class[_] = classOf[TestAnnouncerConfig]
  override def configId: String = "test"
}

object TestAnnouncerInitializer extends TestAnnouncerInitializer

class TestAnnouncerConfig extends AnnouncerConfig {
  override def mk(): Announcer = new TestAnnouncer
}

class TestAnnouncer extends Announcer {
  override val scheme: String = "test"

  var services: Map[String, Set[InetSocketAddress]] = Map.empty

  override def announce(addr: InetSocketAddress, name: String): Future[Announcement] = synchronized {
    services = services + (name -> (services(name) + addr))
    Future.value(new Announcement {
      override def unannounce(): Future[Unit] = synchronized {
        services = services + (name -> (services(name) - addr))
        Future.Unit
      }
    })
  }
}
