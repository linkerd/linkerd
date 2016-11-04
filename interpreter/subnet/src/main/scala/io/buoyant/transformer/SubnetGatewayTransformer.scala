package io.buoyant.transformer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.{Addr, Name, Address, NameTree}
import com.twitter.util.{Var, Activity}
import io.buoyant.namer.{DelegateTree, DelegatingNameTreeTransformer}
import java.net.InetAddress

/**
 * Transforms a bound name tree to only include addresses in
 * `gatewayTree` that are in the same subnet of the original address.
 */
class SubnetGatewayTransformer(
  gatewayTree: Activity[NameTree[Bound]],
  netmask: Netmask
) extends GatewayTransformer(gatewayTree, netmask.local)

class MetadataGatewayTransformer(
  gatewayTree: Activity[NameTree[Bound]],
  metadataField: String
) extends GatewayTransformer(gatewayTree, {
  case (Address.Inet(_, a), Address.Inet(_, b)) => a.get(metadataField) == b.get(metadataField)
  case _ => true
})

class GatewayTransformer(
  gatewayTree: Activity[NameTree[Bound]],
  gatewayPredicate: (Address, Address) => Boolean
) extends DelegatingNameTreeTransformer {

  override protected def transformDelegate(tree: DelegateTree[Bound]): Activity[DelegateTree[Bound]] =
    gatewayTree.map { gateways =>
      val routable = flatten(gateways.eval.toSet.flatten)
      tree.flatMap { leaf =>
        DelegateTree.Transformation(
          leaf.path,
          getClass.getSimpleName,
          leaf.value,
          leaf.copy(value = mapBound(leaf.value, routable))
        )
      }
    }

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] =
    gatewayTree.map { gateways =>
      val routable = flatten(gateways.eval.toSet.flatten)
      tree.map(mapBound(_, routable))
    }

  /** Smoosh together all of the bound addresses into a single Var */
  private[this] def flatten(bounds: Set[Name.Bound]): Var[Addr] =
    Var.collect(bounds.map(_.addr)).map { addrs =>
      val collectedAddresses = addrs.flatMap {
        case Addr.Bound(addresses, _) => addresses
        case _ => Set.empty[Address]
      }
      Addr.Bound(collectedAddresses)
    }

  /**
   * Return a new Bound with the address replaced by a member of the
   * set of gateways ont he same subnet.
   */
  private[this] def mapBound(bound: Name.Bound, gateway: Var[Addr]): Name.Bound = {
    val vaddr = Var.collect(List(bound.addr, gateway)).map {
      case List(Addr.Bound(addrs, meta), Addr.Bound(gatewayAddrs, _)) =>
        val selected = addrs.flatMap { addr =>
          // select the gateway addresses that share a subnet with addr
          gatewayAddrs.filter(gatewayPredicate(addr, _))
        }
        Addr.Bound(selected, meta)
      case List(addr, _) => addr
    }
    Name.Bound(vaddr, bound.id, bound.path)
  }
}
