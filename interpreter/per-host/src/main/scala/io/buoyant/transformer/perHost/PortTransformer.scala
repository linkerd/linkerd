package io.buoyant.transformer.perHost

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.util.Activity
import io.buoyant.namer.{DelegateTree, DelegatingNameTreeTransformer}
import java.net.InetSocketAddress

/**
 * The port transformer replaces the port number in every addresses with a
 * configured value.  This can be used if there is an incoming linkerd router
 * (or other reverse-proxy) running on a fixed port on each host and you with
 * to send traffic to that port instead of directly to the destination address.
 */
class PortTransformer(prefix: Path, port: Int) extends DelegatingNameTreeTransformer {

  private[this] val mapAddress: Address => Address = {
    case Address.Inet(addr, meta) =>
      Address.Inet(new InetSocketAddress(addr.getAddress, port), meta)
    case address => address
  }

  private[this] val mapBound: Name.Bound => Name.Bound = { bound =>
    val vaddr = bound.addr.map {
      case Addr.Bound(addrs, meta) =>
        Addr.Bound(addrs.map(mapAddress), meta)
      case addr => addr
    }
    bound.id match {
      case id: Path => Name.Bound(vaddr, prefix ++ id, bound.path)
      case _ => Name.Bound(vaddr, bound.id, bound.path)
    }
  }

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] =
    Activity.value(tree.map(mapBound))

  override protected def transformDelegate(tree: DelegateTree[Bound]): Activity[DelegateTree[Bound]] =
    Activity.value(DelegatingNameTreeTransformer.transformDelegate(tree, mapBound))
}
