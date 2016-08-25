package io.buoyant.transformer.perHost

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.{Addr, Address, Name, NameTree}
import com.twitter.util.Activity
import io.buoyant.namer.{DelegateTree, DelegatingNameTreeTransformer}
import java.net.InetSocketAddress

class PortTransformer(port: Int) extends DelegatingNameTreeTransformer {

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
    Name.Bound(vaddr, bound.id, bound.path)
  }

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] =
    Activity.value(tree.map(mapBound))

  override protected def transformDelegate(tree: DelegateTree[Bound]): DelegateTree[Bound] =
    tree.map(mapBound)
}
