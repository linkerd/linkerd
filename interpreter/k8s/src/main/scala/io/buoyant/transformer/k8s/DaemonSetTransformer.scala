package io.buoyant.transformer.k8s

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.{Addr, Name, Address, NameTree}
import com.twitter.util.{Var, Activity}
import io.buoyant.namer.{DelegateTree, DelegatingNameTreeTransformer}

/**
 * The DaemonSetTransformer maps each address in the NameTree to a member of
 * a given daemon set that is on the same /24 subnet.  Since each k8s node
 * is its own /24 subnet, the result is that each address is mapped to the
 * member of the daemon set that is running on the same node.  This can be used
 * to redirect traffic to a reverse-proxy that runs as a daemon set.
 */
class DaemonSetTransformer(daemonSet: Activity[NameTree[Bound]]) extends DelegatingNameTreeTransformer {

  override protected def transformDelegate(tree: DelegateTree[Bound]): Activity[DelegateTree[Bound]] = {
    daemonSet.map { daemonSet =>
      val daemons = flatten(daemonSet.eval.toSet.flatten)
      tree.map(mapBound(_, daemons))
    }
  }

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] = {
    daemonSet.map { daemonSet =>
      val daemons = flatten(daemonSet.eval.toSet.flatten)
      tree.map(mapBound(_, daemons))
    }
  }

  /** Smoosh together all of the bound addresses into a single Var */
  private[this] def flatten(bounds: Set[Name.Bound]): Var[Addr] = {
    Var.collect(bounds.map(_.addr)).map { addrs =>
      val collectedAddresses = addrs.flatMap {
        case Addr.Bound(addresses, _) => addresses
        case _ => Set.empty[Address]
      }
      Addr.Bound(collectedAddresses)
    }
  }

  /**
   * Return a new Bound with the address replaced by a member of the daemon
   * set on the same /24 subnet
   */
  private[this] def mapBound(bound: Name.Bound, daemons: Var[Addr]): Name.Bound = {
    val vaddr = Var.collect(List(bound.addr, daemons)).map {
      case List(Addr.Bound(addrs, meta), Addr.Bound(daemon, _)) =>
        val selected = addrs.flatMap { addr =>
          // select the daemonset addresses that share a subnet with addr
          daemon.filter(shareSubnet(addr, _))
        }
        Addr.Bound(selected, meta)
      case List(addr, _) => addr
    }
    Name.Bound(vaddr, bound.id, bound.path)
  }

  private[this] def shareSubnet(a1: Address, a2: Address): Boolean = {
    (a1, a2) match {
      case (Address.Inet(addr1, _), Address.Inet(addr2, _)) =>
        val b1 = addr1.getAddress.getAddress
        val b2 = addr2.getAddress.getAddress
        // examine only the first 3 bytes (24 bits)
        b1.zip(b2).take(3).forall(tupleEqual)
      case _ => false
    }
  }

  private[this] val tupleEqual: Tuple2[Byte, Byte] => Boolean = {
    case (b1, b2) => b1 == b2
  }
}
