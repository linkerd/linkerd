package io.buoyant.namer.zk

import com.twitter.finagle.util.InetSocketAddressUtil
import com.twitter.finagle.{Group => _, _}
import com.twitter.util._
import io.buoyant.config.types.HostAndPort
import java.net.InetSocketAddress

import com.twitter.common.zookeeper.{Candidate, CandidateImpl}
import com.twitter.finagle.common.zookeeper.Group.GroupChangeListener
import com.twitter.finagle.common.zookeeper.{Group, ZooKeeperClient, ZooKeeperUtils}
import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.data.ACL

import scala.collection.JavaConverters._

/**
 * This namer accepts paths of the form /<prefix>/<zkPath>.  The zkPath is the location
 * in ZooKeeper of a leader group.  This namer resolves to the addresses stored in the data of
 * the leader of the group.
 */
case class ZkLeaderNamer(
  prefix: Path,
  zkAddrs: Seq[HostAndPort],
  factory: Iterable[InetSocketAddress] => ZooKeeperClient
) extends Namer {

  def this(prefix: Path, zkAddrs: Seq[HostAndPort]) = this(
    prefix,
    zkAddrs,
    h => new ZooKeeperClient(ZooKeeperUtils.DEFAULT_ZK_SESSION_TIMEOUT, h.asJava)
  )

  override def lookup(path: Path): Activity[NameTree[Name]] = bind(path)

  private[this] val client = factory(zkAddrs.map(_.toInetSocketAddress))

  private[this] def bind(path: Path, residual: Path = Path.empty): Activity[NameTree[Name]] = {
    val id = prefix ++ path

    val group = new Group(client, Iterable.empty[ACL].asJava, path.show)
    val candidate = new CandidateImpl(group)
    leaderAddr(candidate).map { initial =>
      Var.async[Addr](initial) { update =>
        val stop = group.watch(
          new GroupChangeListener {
            override def onGroupChange(memberIds: java.lang.Iterable[String]): Unit = {
              leaderAddr(candidate) match {
                case Some(addr) => update() = addr
                case None => update() = Addr.Neg
              }
            }
          }
        )

        Closable.make { _ =>
          stop.run()
          Future.Unit
        }
      }
    } match {
      case Some(addr) =>
        val tree = addr.map[Activity.State[NameTree[Name]]] {
          case bound: Addr.Bound => Activity.Ok(NameTree.Leaf(Name.Bound(addr, id, residual)))
          case _ => Activity.Ok(NameTree.Neg)
        }
        Activity(tree)
      case None =>
        if (!path.isEmpty) {
          val n = path.size
          bind(path.take(n - 1), residual ++ path.drop(n - 1))
        } else {
          Activity.value(NameTree.Neg)
        }
    }
  }

  private[this] def leaderAddr(candidate: Candidate): Option[Addr] = {
    try {
      Option(candidate.getLeaderData.orNull).map { data =>
        val hosts = InetSocketAddressUtil.parseHosts(new String(data, "UTF-8")).toSet
        Addr.Bound(hosts.map(Address(_)))
      }
    } catch {
      case e: NoNodeException =>
        None
    }
  }
}
