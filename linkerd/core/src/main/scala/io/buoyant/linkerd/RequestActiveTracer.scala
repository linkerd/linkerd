package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http._
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util._
import io.buoyant.namer.DelegateTree._
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.router.RouterLabel
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}

class RequestActiveTracer(
  endpoint: EndpointAddr,
  namer: DstBindingFactory.Namer,
  dtab: BaseDtab,
  routerLabel: String
) extends SimpleFilter[Request, Response] {

  private[this] case class Node(path: String, dentry: String, treeNode: DelegateTree[_]) {
    override def toString: String = {
      treeNode match {
        case DelegateTree.Delegate(_, d, _) if d == Dentry.nop => s"$path"
        case _ => s"$path ($dentry)"
      }

    }
  }

  private[this] val RequestTracerMaxDepthHeader = "l5d-max-depth"

  private[this] def printEvaluatedRequest(
    prependText: String,
    serviceName: String,
    clientName: String,
    selectedAddress: String,
    addresses: Option[Set[String]],
    dtabResolution: List[String]
  ): String = {
    Seq(
      prependText,
      s"""
         |--- Router: $routerLabel ---
         |service name: $serviceName
         |client name: $clientName
         |addresses: [${addresses.getOrElse(Set.empty).mkString(", ")}]
         |selected address: $selectedAddress
         |dtab resolution:
         |${dtabResolution.map("  " + _).mkString("\n")}
    """.stripMargin
    ).mkString("\n").trim.concat("\n")
  }

  /**
   * formatDelegation prints out path the proxy used to resolve the client name.
   * It 'walks' a DelegateTree of Name.Bound and finds a DelegateLeaf that matches
   * the client path provided.
   * @param dTree
   * @param tree
   * @param searchPath
   * @return
   */
  private[this] def formatDelegation(
    dTree: DelegateTree[Name.Bound],
    tree: List[Node],
    searchPath: Path
  ): List[Node] = {
    dTree match {
      case l@Leaf(leafPath, dentry, bound) if bound.id == searchPath =>
        tree.lastOption.map {
          case Node(nodePath, nodeDentry, Transformation(_, _, _, _)) =>
            tree.dropRight(1) :+ Node(
              nodePath,
              dentry.show,
              DelegateTree.Empty(Path.empty, Dentry.nop)
            ) :+ Node(leafPath.show, nodeDentry, DelegateTree.Empty(Path.empty, Dentry.nop))
          case _ => tree :+ Node(leafPath.show, dentry.show, l)
        }.getOrElse(List.empty)
      case t@Transformation(path, name, _, remainingTree) =>
        formatDelegation(remainingTree, tree :+ Node(path.show, name, t), searchPath)
      case d@Delegate(path, dentry, remainingTree) =>
        formatDelegation(remainingTree, tree :+ Node(path.show, dentry.show, d), searchPath)
      case u@Union(path, dentry, remainingWeightedTrees@_*) =>
        remainingWeightedTrees.map { wd =>
          formatDelegation(wd.tree, tree :+ Node(path.show, dentry.show, u), searchPath)
        }.find(!_.isEmpty).toList.flatten
      case a@Alt(path, dentry, remainingTrees@_*) =>
        remainingTrees.map { d =>
          formatDelegation(d, tree :+ Node(path.show, dentry.show, a), searchPath)
        }.find(!_.isEmpty).toList.flatten
      case _ => List.empty
    }
  }

  private[this] def getRequestTraceResponse(prevResp: Response) = {

    val serviceName = DstPathCtx.current match {
      case Some(dstPath) => dstPath.path
      case None => Path.empty
    }

    val selectedEndpoint = endpoint.addr match {
      case inetAddr: Address.Inet => inetAddr.addr.toString.stripPrefix("/")
      case _ => ""
    }

    val clientPath = DstBoundCtx.current match {
      case None => Path.empty
      case Some(addrSet) => addrSet.name.id match {
        case p: Path => p
        case _ => Path.empty
      }
    }

    val lbSet = DstBoundCtx.current match {
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

    val dtreeF = namer.interpreter match {
      case delegator: Delegator => delegator.delegate(dtab.dtab(), serviceName)
        .map(Some(_))
      case _ => Future.None
    }

    dtreeF.joinWith(addresses) {
      case (dTree: Option[DelegateTree[Name.Bound]], addrSet: Option[Set[String]]) =>
        val tree = formatDelegation(dTree.getOrElse(DelegateTree.Empty(Path.empty, Dentry.nop)), List.empty, clientPath)
        prevResp.contentString = printEvaluatedRequest(
          prevResp.contentString,
          serviceName.show,
          clientPath.show,
          selectedEndpoint,
          addrSet,
          tree.foldLeft(List[String]())((a, b) => a :+ b.toString)
        )
        //We set the content length of the response in order to view the content string entirely
        prevResp.contentLength = prevResp.content.length
        prevResp
    }
  }

  override def apply(
    req: Request,
    svc: Service[Request, Response]
  ): Future[Response] = {

    val maxTraceDepth = Try(req.headerMap.get(RequestTracerMaxDepthHeader).map(_.toInt))

    (req.method, maxTraceDepth) match {
      case (Method.Trace, Throw(_: NumberFormatException)) =>
        val resp = Response()
        resp.contentString = s"Invalid value for $RequestTracerMaxDepthHeader header"
        Future.value(resp)
      case (Method.Trace, Return(Some(0))) =>
        getRequestTraceResponse(Response())
      case (Method.Trace, Return(Some(num))) if num > 0 =>
        req.headerMap.set(RequestTracerMaxDepthHeader, (num - 1).toString)
        svc(req).flatMap(getRequestTraceResponse).ensure {
          req.headerMap.set(RequestTracerMaxDepthHeader, num.toString); ()
        }
      case (_, _) => svc(req)
    }
  }
}

object RequestActiveTracer {

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module4[EndpointAddr, RoutingFactory.BaseDtab, DstBindingFactory.Namer, RouterLabel.Param, ServiceFactory[Request, Response]] {

      override def role: Stack.Role = Stack.Role("RequestEvaluator")

      override def description: String = "Intercepts to respond with useful client destination info"

      override def make(
        endpoint: EndpointAddr,
        dtab: BaseDtab,
        interpreter: DstBindingFactory.Namer,
        label: RouterLabel.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        new RequestActiveTracer(endpoint, interpreter, dtab, label.label).andThen(next)
    }
}

