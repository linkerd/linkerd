package io.buoyant.linkerd

import com.twitter.finagle.{Path, Announcement}
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

  override def concreteName(name: String, version: Option[String]): Path =
    Path.read(s"/#/io.l5d.test/$name")

  override def announce(
    addr: InetSocketAddress,
    name: String,
    version: Option[String] = None
  ): Future[Announcement] = synchronized {
    services = services + (name -> (services(name) + addr))
    Future.value(new Announcement {
      override def unannounce(): Future[Unit] = synchronized {
        services = services + (name -> (services(name) - addr))
        Future.Unit
      }
    })
  }
}
