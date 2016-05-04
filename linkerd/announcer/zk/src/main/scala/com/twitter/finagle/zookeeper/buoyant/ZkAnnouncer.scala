package com.twitter.finagle.zookeeper.buoyant

import com.twitter.finagle.{Announcement, Announcer}
import com.twitter.finagle.zookeeper.{ZkAnnouncer => FZkAnnouncer, DefaultZkClientFactory}
import com.twitter.util.Future
import java.net.InetSocketAddress

class ZkAnnouncer(hosts: String, pathPrefix: String) extends Announcer {
  override val scheme: String = "zk"

  val underlying = new FZkAnnouncer(DefaultZkClientFactory)

  override def announce(addr: InetSocketAddress, name: String): Future[Announcement] =
    underlying.announce(addr, s"$hosts!$pathPrefix/$name!0")
}
