package io.buoyant.linkerd

import com.twitter.finagle.{Announcement, Path}
import com.twitter.util.Future
import java.net.InetSocketAddress

abstract class Announcer {
  val scheme: String

  def announce(addr: InetSocketAddress, name: String, version: Option[String] = None): Future[Announcement]

  def concreteName(name: String, version: Option[String] = None): Path
}
