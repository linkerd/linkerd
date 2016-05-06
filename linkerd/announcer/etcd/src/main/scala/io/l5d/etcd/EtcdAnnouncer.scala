package io.l5d.etcd

import com.twitter.finagle.{Announcement, Announcer, Service}
import com.twitter.finagle.http.{Response, Request}
import com.twitter.io.Buf
import com.twitter.util.{Timer, Duration, Future}
import io.buoyant.etcd.Etcd
import java.net.InetSocketAddress

class EtcdAnnouncer(
  client: Service[Request, Response],
  pathPrefix: String,
  ttl: Duration,
  refresh: Duration
)(implicit timer: Timer) extends Announcer {
  override val scheme: String = "etcd"

  val etcd = new Etcd(client)
  val root = etcd.key(pathPrefix)

  override def announce(addr: InetSocketAddress, name: String): Future[Announcement] = {
    val buf = Buf.Utf8(addr.toString)
    root.createInOrderKey(Some(buf), Some(ttl)).map { nodeOp =>
      val key = etcd.key(nodeOp.node.key)
      val task = timer.schedule(refresh) {
        key.refresh(Some(ttl))
      }
      new Announcement {
        override def unannounce(): Future[Unit] = {
          task.cancel()
          Future.Unit
        }
      }
    }
  }
}
