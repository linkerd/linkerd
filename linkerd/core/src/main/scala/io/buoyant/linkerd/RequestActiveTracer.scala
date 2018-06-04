package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http._
import com.twitter.finagle.http.Status
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util._
import io.buoyant.namer.DelegateTree._
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.router.RouterLabel
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}

/**
 * Intercepts Http TRACE requests with an l5d-max-depth header.
 * Returns identification, delegation, and service address information
 * for a given router as well as any other TRACE responses received from downstream
 * services.
 *
 * A router that receives a tracer request with an l5d-max-depth
 * greater than 0 decrements the l5d-max-depth header by 1 and
 * forwards the tracer request to a downstream service that may perform
 * additional processing of the request.
 *
 * @param endpoint the endpoint address of a downstream service identified by a router
 * @param namers namers used to evaluate a service name
 * @param dtab base dtab used by the router
 * @param routerLabel name of router
 */
class RequestActiveTracer(
  endpoint: EndpointAddr,
  namers: DstBindingFactory.Namer,
  dtab: BaseDtab,
  routerLabel: String
) extends SimpleFilter[Request, Response] {

  private[this] case class PathNode(
    path: String,
    dentry: String,
    dTreeNode: DelegateTree[_] = DelegateTree.Empty(Path.empty, Dentry.nop)) {
    override def toString: String = {
      dTreeNode match {
        case DelegateTree.Delegate(_, d, _) if d == Dentry.nop => s"$path"
        case _ => s"$path ($dentry)"
      }

    }
  }

  private[this] val RequestTracerMaxDepthHeader = "l5d-max-depth"

  private def formatRouterContext(
    prependText: String,
    serviceName: String,
    clientName: String,
    selectedAddress: String,
    addresses: Option[Set[String]],
    dtabResolution: List[String],
    elapsedTimestamp: String
  ) = {
    Seq(
      prependText,
      s"""
      |--- Router: $routerLabel ---
      |request duration: $elapsedTimestamp ms
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
   * Returns a list of [[PathNode]] that represents a delegate tree path that identifies how a
   * router binds a service name.
   *
   * @param dTree DelegateTree with a bound name.
   * @param nodes an accumulator of [[PathNode]] that reveal the path to a client name.
   * @param clientName the client path name this method is searching for in a [[DelegateTree]]
   * @return list of [[PathNode]]. An empty list if no path was found.
   */
  private[this] def formatDelegation(
    dTree: DelegateTree[Name.Bound],
    nodes: List[PathNode],
    clientName: Path
  ): List[PathNode] = {

    //walk the delegate tree to find the path of the clientName path.
    dTree match {

        //when we reach a DelegateTree.Leaf we may have found a path.
      case l@Leaf(leafPath, dentry, bound) if bound.id == clientName =>

        // We need to check if the last node in 'nodes' is of type DelegationTree.Transformation.
        // If so, we need to switch the transformation's dentry with
        // the current leaf's dentry. We do this to make sure that the node list is more readable
        // when it is added to the request active tracer's response.
        val finalPath = nodes.lastOption.collect {
          case PathNode(nodePath, nodeDentry, _: Transformation[_]) => // check if is transformation

            // Switch dentries
            val transformerNode = PathNode(nodePath, dentry.show)
            val leafNode = PathNode(leafPath.show, nodeDentry)

            nodes.dropRight(1) :+ transformerNode :+ leafNode
          case _ =>
            // If the last node is not a transformation, it's safe to add the leaf node
            // as the last element of nodes
            val leafNode = PathNode(leafPath.show, dentry.show, l)
            nodes :+ leafNode
        }
        finalPath.getOrElse(List.empty) // otherwise return  an empty list indicating there is no path.
      case t@Transformation(path, name, _, remainingTree) =>
        formatDelegation(remainingTree, nodes :+ PathNode(path.show, name, t), clientName)
      case d@Delegate(path, dentry, remainingTree) =>
        formatDelegation(remainingTree, nodes :+ PathNode(path.show, dentry.show, d), clientName)
      case u@Union(path, dentry, remainingWeightedTrees@_*) =>
        remainingWeightedTrees.map { wd =>
          formatDelegation(wd.tree, nodes :+ PathNode(path.show, dentry.show, u), clientName)
        }.find(!_.isEmpty).toList.flatten
      case a@Alt(path, dentry, remainingTrees@_*) =>
        remainingTrees.map { d =>
          formatDelegation(d, nodes :+ PathNode(path.show, dentry.show, a), clientName)
        }.find(!_.isEmpty).toList.flatten
      case _ => List.empty
    }
  }

  private def getRequestTraceResponse(resp: Response, stopwatch: Stopwatch.Elapsed) = {

    val EmptyDelegateTree = DelegateTree.Empty(Path.empty, Dentry.nop)

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

    val dtreeF = namers.interpreter match {
      case delegator: Delegator => delegator.delegate(dtab.dtab(), serviceName)
        .map(Some(_))
      case _ => Future.None
    }

    dtreeF.joinWith(addresses) {
      case (dTree: Option[DelegateTree[Name.Bound]], addrSet: Option[Set[String]]) =>
        val tree = formatDelegation(dTree.getOrElse(EmptyDelegateTree), List.empty, clientPath)

        resp.contentString = formatRouterContext(
          resp.contentString,
          serviceName.show,
          clientPath.show,
          selectedEndpoint,
          addrSet,
          tree.map(_.toString),
          stopwatch().inMillis.toString
        )

        //We set the content length of the response in order to view the content string entirely
        resp.contentLength = resp.content.length
        resp
    }
  }

  override def apply(
    req: Request,
    svc: Service[Request, Response]
  ): Future[Response] = {

    val maxTraceDepth = Try(req.headerMap.get(RequestTracerMaxDepthHeader).map(_.toInt))

    (req.method, maxTraceDepth) match {
      case (Method.Trace, Throw(_: NumberFormatException)) =>
        val resp = Response(Status.BadRequest)
        resp.contentString = s"Invalid value for $RequestTracerMaxDepthHeader header"
        Future.value(resp)
      case (Method.Trace, Return(Some(0))) =>
        val stopwatch = Stopwatch.start()
        getRequestTraceResponse(Response(), stopwatch)
      case (Method.Trace, Return(Some(num))) if num > 0 =>
        val stopwatch = Stopwatch.start()
        req.headerMap.set(RequestTracerMaxDepthHeader, (num - 1).toString)
        svc(req).flatMap((prevResp: Response) => getRequestTraceResponse(prevResp, stopwatch)).ensure {
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

