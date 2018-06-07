package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util.{Future, Stopwatch}
import io.buoyant.linkerd.ActiveTracer.formatDelegation
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.namer.DelegateTree._
import io.buoyant.router.RoutingFactory.BaseDtab

private[linkerd] case class RouterContext(
  routerLabel: String,
  elapsed: Stopwatch.Elapsed,
  serviceName: String,
  clientName: String,
  selectedAddresses: Option[Set[String]],
  selectedAddress: String,
  dtabResolution: List[String]
) {
  def formatRouterContext: String = {
    s"""|--- Router: $routerLabel ---
          |request duration: ${elapsed().inMillis.toString} ms
          |service name: $serviceName
          |client name: $clientName
          |addresses: [${selectedAddresses.getOrElse(Set.empty).mkString(", ")}]
          |selected address: $selectedAddress
          |dtab resolution:
          |${dtabResolution.map("  " + _).mkString(System.lineSeparator)}
          |""".stripMargin
  }
}

private[linkerd] object RouterContextBuilder {
  val EmptyDelegateTree = DelegateTree.Empty(Path.empty, Dentry.nop)

  def apply(
    routerLabel: String,
    elapsed: Stopwatch.Elapsed,
    pathCtx: Option[Dst.Path],
    boundCtx: Option[Dst.Bound],
    endpoint: EndpointAddr,
    namers: DstBindingFactory.Namer,
    dtabs: BaseDtab
  ): Future[RouterContext] = {
    val serviceName = pathCtx match {
      case Some(dstPath) => dstPath.path
      case None => Path.empty
    }

    val selectedEndpoint = endpoint.addr match {
      case inetAddr: Address.Inet => inetAddr.addr.toString.stripPrefix("/")
      case _ => ""
    }

    val clientPath = boundCtx match {
      case None => Path.empty
      case Some(addrSet) => addrSet.name.id match {
        case p: Path => p
        case _ => Path.empty
      }
    }

    val lbSet = boundCtx match {
      case None => Future.value(Addr.Neg)
      case Some(addrSet) => addrSet.name.addr.changes.toFuture
    }

    val addresses = lbSet.map {
      case Addr.Bound(a, _) => Some(
        a.map {
          case inetAddr: Address.Inet => inetAddr.addr.toString.stripPrefix("/")
          case _ => ""
        }
      )
      case _ => None
    }

    val dtreeF = namers.interpreter match {
      case delegator: Delegator => delegator.delegate(dtabs.dtab(), serviceName)
        .map(Some(_))
      case _ => Future.None
    }

    dtreeF.joinWith(addresses) {
      case (dTree: Option[DelegateTree[Name.Bound]], addrSet: Option[Set[String]]) =>
        val tree = formatDelegation(dTree.getOrElse(EmptyDelegateTree), List.empty, clientPath)

        new RouterContext(
          routerLabel,
          elapsed,
          serviceName.show,
          clientPath.show,
          addrSet,
          selectedEndpoint,
          tree.map(_.toString)
        )
    }
  }
}

object ActiveTracer {

  // Ensure we use an empty string over the Dentry.nop default string
  private[this] val checkDentryNop = (dentry: Dentry) => if (dentry == Dentry.nop) "" else dentry.show

  private[linkerd] case class DelegationNode(
    path: String,
    dentry: String,
    dTreeNode: DelegateTree[_] = DelegateTree.Empty(Path.empty, Dentry.nop)
  ) {
    override def toString: String = if (dentry.length == 0) s"$path" else s"$path ($dentry)"
  }

  /**
   * Returns a list of DelegationNode that represents a delegate tree path that identifies how a
   * router binds a service name.
   *
   * @param dTree DelegateTree with a bound name.
   * @param nodes an accumulator of DelegationNode that reveal the path to a client name.
   * @param clientName the client path name this method is searching for in a DelegateTree
   * @return list of DelegationNode. An empty list if no path was found.
   */
  private[linkerd] def formatDelegation(
    dTree: DelegateTree[Name.Bound],
    nodes: List[DelegationNode],
    clientName: Path
  ): List[DelegationNode] = {

    //walk the delegate tree to find the path of the clientName path.
    dTree match {

      //when we reach a DelegateTree.Leaf we may have found a path.
      case l@Leaf(leafPath, dentry, bound) if bound.id == clientName =>

        // We need to check if the last node in 'nodes' is of type DelegationTree.Transformation.
        // If so, we need to switch the transformation's dentry with
        // the current leaf's dentry. We do this to make sure that the node list is more readable
        // when it is added to the request active tracer's response.
        val finalPath = nodes.lastOption.collect {
          case DelegationNode(nodePath, nodeDentry, _: Transformation[_]) => // check if is transformation

            // Switch dentries
            val transformerNode = DelegationNode(nodePath, checkDentryNop(dentry))
            val leafNode = DelegationNode(leafPath.show, nodeDentry)

            nodes.dropRight(1) :+ transformerNode :+ leafNode
          case _ =>
            // If the last node is not a transformation, it's safe to add the leaf node
            // as the last element of nodes
            val leafNode = DelegationNode(leafPath.show, dentry.show, l)
            nodes :+ leafNode
        }
        finalPath.getOrElse(List.empty) // otherwise return  an empty list indicating there is no path.
      case t@Transformation(path, name, _, remainingTree) =>
        formatDelegation(remainingTree, nodes :+ DelegationNode(path.show, name, t), clientName)
      case d@Delegate(path, dentry, remainingTree) =>
        formatDelegation(remainingTree, nodes :+ DelegationNode(path.show, checkDentryNop(dentry), d), clientName)
      case u@Union(path, dentry, remainingWeightedTrees@_*) =>
        remainingWeightedTrees.map { wd =>
          formatDelegation(wd.tree, nodes :+ DelegationNode(path.show, checkDentryNop(dentry), u), clientName)
        }.find(!_.isEmpty).toList.flatten
      case a@Alt(path, dentry, remainingTrees@_*) =>
        remainingTrees.map { d =>
          formatDelegation(d, nodes :+ DelegationNode(path.show, checkDentryNop(dentry), a), clientName)
        }.find(!_.isEmpty).toList.flatten
      case _ => List.empty
    }
  }
}
