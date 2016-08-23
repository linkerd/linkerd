package com.twitter.finagle.zookeeper.buoyant

import com.twitter.finagle.{Path, Announcement}
import com.twitter.finagle.zookeeper.{ZkAnnouncer => FZkAnnouncer, DefaultZkClientFactory}
import com.twitter.util.Future
import io.buoyant.config.types.HostAndPort
import io.buoyant.linkerd.Announcer
import java.net.InetSocketAddress

class ZkAnnouncer(zkAddrs: Seq[HostAndPort], pathPrefix: Path) extends Announcer {
  override val scheme: String = "zk-serversets"

  val underlying = new FZkAnnouncer(DefaultZkClientFactory)

  private[this] val connect = zkAddrs.map(_.toString).mkString(",")

  override def announce(addr: InetSocketAddress, name: Path): Future[Announcement] =
    underlying.announce(addr, s"$connect!${(pathPrefix ++ name).show}!0")
}
