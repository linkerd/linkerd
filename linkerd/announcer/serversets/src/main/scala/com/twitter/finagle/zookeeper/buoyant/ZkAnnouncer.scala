package com.twitter.finagle.zookeeper.buoyant

import com.twitter.finagle.{Path, Announcement}
import com.twitter.finagle.zookeeper.{ZkAnnouncer => FZkAnnouncer, DefaultZkClientFactory}
import com.twitter.util.Future
import io.buoyant.config.types.HostAndPort
import io.buoyant.linkerd.Announcer
import java.net.InetSocketAddress

class ZkAnnouncer(zkAddrs: Seq[HostAndPort], pathPrefix: String) extends Announcer {
  override val scheme: String = "zk-serversets"

  val underlying = new FZkAnnouncer(DefaultZkClientFactory)

  private[this] val connect = zkAddrs.map(_.toString).mkString(",")

  override def announce(addr: InetSocketAddress, name: String, version: Option[String] = None): Future[Announcement] =
    version match {
      case Some(v) => underlying.announce(addr, s"$connect!$pathPrefix/$name-$v!0")
      case None => underlying.announce(addr, s"$connect!$pathPrefix/$name!0")
    }

  private[this] val prefix = Path.read(pathPrefix)
  override def concreteName(name: String, version: Option[String] = None): Path =
    version match {
      case Some(v) => Path.Utf8("#", "io.l5d.serversets") ++ prefix ++ Path.Utf8(s"$name-$v")
      case None => Path.Utf8("#", "io.l5d.serversets") ++ prefix ++ Path.Utf8(name)
    }
}
