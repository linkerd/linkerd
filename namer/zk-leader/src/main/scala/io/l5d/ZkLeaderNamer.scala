package io.l5d

import com.twitter.finagle.util.InetSocketAddressUtil
import org.apache.zookeeper.data.ACL
import scala.collection.JavaConverters._
import com.twitter.common.zookeeper.{Group, CandidateImpl, ZooKeeperUtils, ZooKeeperClient}
import com.twitter.finagle.{Name, NameTree, Path, Namer}
import com.twitter.util.Activity
import java.net.InetSocketAddress

class ZkLeaderNamer(factory: Iterable[InetSocketAddress] => ZooKeeperClient) extends Namer {

  def this() = this(hosts => new ZooKeeperClient(ZooKeeperUtils.DEFAULT_ZK_SESSION_TIMEOUT, hosts.asJava))

  override def lookup(path: Path): Activity[NameTree[Name]] = {
    path.take(2) match {
      case Path.Utf8(hosts, zkPath) =>
        val client = factory(InetSocketAddressUtil.parseHosts(hosts).toSet)
        val candidate = new CandidateImpl(new Group(client, Iterable.empty[ACL].asJava, zkPath))
        Option(candidate.getLeaderData.orNull)
      case _ =>
        None
    }
    ???
  }
}
