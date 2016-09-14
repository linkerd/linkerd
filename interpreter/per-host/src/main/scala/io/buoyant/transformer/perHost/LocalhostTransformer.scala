package io.buoyant.transformer.perHost

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.{Addr, Address, Name, NameTree}
import com.twitter.util.Activity
import io.buoyant.namer.{DelegateTree, DelegatingNameTreeTransformer}
import java.net.InetAddress

/**
 * The localhost transformer filters the list of addresses down to only
 * addresses that have the same IP address as localhost.  This can be used by
 * an incoming router to only route traffic to local destinations.
 */
class LocalhostTransformer extends DelegatingNameTreeTransformer {

  private[this] val localhost = InetAddress.getLocalHost.getAddress

  private[this] val tupleEqual: Tuple2[Byte, Byte] => Boolean = {
    case (b1, b2) => b1 == b2
  }

  private[this] val isLocal: Address => Boolean = {
    case Address.Inet(addr, meta) =>
      val bytes = addr.getAddress.getAddress
      localhost.zip(bytes).forall(tupleEqual)
    case address => true
  }

  private[this] val mapBound: Name.Bound => Name.Bound = { bound =>
    val vaddr = bound.addr.map {
      case Addr.Bound(addrs, meta) =>
        Addr.Bound(addrs.filter(isLocal), meta)
      case addr => addr
    }
    Name.Bound(vaddr, bound.id, bound.path)
  }

  override protected def transformDelegate(tree: DelegateTree[Bound]): DelegateTree[Bound] =
    tree.map(mapBound)

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] =
    Activity.value(tree.map(mapBound))
}
