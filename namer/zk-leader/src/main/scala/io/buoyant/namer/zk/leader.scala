package io.buoyant.namer.zk

import com.twitter.finagle.util.InetSocketAddressUtil
import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.util.Activity
import io.buoyant.config.types.{Port, HostAndPort}

/**
 * This namer accepts paths of the form /<zkHosts>/<zkPath>.  zkHosts is a ::
 * delimited list of zookeeper hostname:port pairs.  The zkPath is the location
 * in ZooKeeper of a leader group.  This namer resolves to the addresses stored
 * in the data of the leader of the group.
 *
 * e.g. `/1.2.3.4:8001::5.6.7.8:8001::9.10.11.12:8001/path/to/leader/group`
 */
class leader extends Namer {

  private[zk] def zkLeaderNamer(path: Path): ZkLeaderNamer = {
    val Path.Utf8(hosts) = path.take(1)
    val zkAddrs = hosts.split("::")
      .flatMap(InetSocketAddressUtil.parseHostPorts).map {
        case (host, port) => HostAndPort(Some(host), Some(Port(port)))
      }
    new ZkLeaderNamer(Path.empty, zkAddrs.toIndexedSeq)
  }

  override def lookup(path: Path): Activity[NameTree[Name]] = {
    zkLeaderNamer(path).lookup(path.drop(1))
  }
}
