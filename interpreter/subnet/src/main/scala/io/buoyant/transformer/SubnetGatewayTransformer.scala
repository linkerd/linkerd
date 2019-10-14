package io.buoyant.transformer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.admin.Admin
import io.buoyant.namer.{DelegateTree, DelegatingNameTreeTransformer, RichActivity}

/**
 * Transforms a bound name tree to only include addresses in
 * `gatewayTree` that are in the same subnet of the original address.
 */
class SubnetGatewayTransformer(
  prefix: Path,
  gatewayTree: Activity[NameTree[Bound]],
  netmask: Netmask,
  handlers: Seq[Admin.Handler] = Seq.empty
) extends GatewayTransformer(prefix, gatewayTree, netmask.local, handlers)

class MetadataGatewayTransformer(
  prefix: Path,
  gatewayTree: Activity[NameTree[Bound]],
  metadataField: String,
  handlers: Seq[Admin.Handler] = Seq.empty
) extends GatewayTransformer(prefix, gatewayTree, {
  case (Address.Inet(_, a), Address.Inet(_, b)) => a.get(metadataField) == b.get(metadataField)
  case _ => true
},
  handlers)

class GatewayTransformer(
  prefix: Path,
  gatewayTree: Activity[NameTree[Bound]],
  gatewayPredicate: (Address, Address) => Boolean,
  handlers: Seq[Admin.Handler] = Seq.empty
) extends DelegatingNameTreeTransformer with Admin.WithHandlers {

  override def adminHandlers: Seq[Admin.Handler] = handlers

  override protected def transformDelegate(tree: DelegateTree[Bound]): Future[DelegateTree[Bound]] =
    gatewayTree.toFuture.map { gateways =>
      val routable = flatten(gateways.eval.toSet.flatten)
      DelegatingNameTreeTransformer.transformDelegate(tree, mapBound(_, routable))
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
        if (selected.isEmpty)
          Addr.Neg
        else
          Addr.Bound(selected, meta)
      case List(addr, _) => addr
    }
    bound.id match {
      case id: Path => Name.Bound(vaddr, prefix ++ id, bound.path)
      case _ => Name.Bound(vaddr, bound.id, bound.path)
    }

  }
}
