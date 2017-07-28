package com.twitter.finagle.zookeeper.buoyant

import com.twitter.finagle.{Announcement, Path}
import com.twitter.finagle.zookeeper.{DefaultZkClientFactory, ZkAnnouncer => FZkAnnouncer}
import com.twitter.util.{Future, Time}
import io.buoyant.config.types.HostAndPort
import io.buoyant.linkerd.FutureAnnouncer
import java.net.InetSocketAddress

class ZkAnnouncer(zkAddrs: Seq[HostAndPort], pathPrefix: Path) extends FutureAnnouncer {
  val scheme: String = "zk-serversets"

  private[this] val underlying = new FZkAnnouncer(DefaultZkClientFactory)

  private[this] val connect = zkAddrs.map(_.toString).mkString(",")

  protected def announceAsync(addr: InetSocketAddress, name: Path): Future[Announcement] =
    underlying.announce(addr, s"$connect!${(pathPrefix ++ name).show}!0")

  // TODO can we close?
  override def close(deadline: Time) = Future.Unit
}
