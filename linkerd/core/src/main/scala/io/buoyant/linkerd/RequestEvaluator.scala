package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http.{Status, _}
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util._
import io.buoyant.namer.DelegateTree._
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.router.RouterLabel
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}

class RequestEvaluator(
  endpoint: EndpointAddr,
  namer: DstBindingFactory.Namer,
  dtab: BaseDtab,
  label: String
) extends SimpleFilter[Request, Response] {

  private[this] val RequestTracerMaxDepthHeader = "l5d-max-depth"

  private[this] def evaluatedRequest(
    routerLabel: String,
    serviceName: String,
    clientName: String,
    selectedAddress: String,
    addresses: Option[Set[String]],
    dtabResolution: List[String]
  ) = {
    s"""
       |--- Router: $routerLabel ---
       |service name: $serviceName
       |client name: $clientName
       |selected address: $selectedAddress
       |addresses: [${addresses.getOrElse(Set.empty).mkString(", ")}]
       |dtab resolution:
       |${dtabResolution.map("  " + _).mkString("\n")}
    """.stripMargin
  }

  private[this] def formatDTree(
    dTree: DelegateTree[Name.Bound],
    tree: List[String],
    searchPath: Path
  ): List[String] = {
    dTree match {
      case Leaf(path, _, _) if path == searchPath =>
        tree :+ path.show
      case Transformation(_, _, value, remainingTree) =>
        formatDTree(remainingTree, tree :+ value.path.show, searchPath)
      case Delegate(path, _, remainingTree) =>
        formatDTree(remainingTree, tree :+ path.show, searchPath)
      case Union(path, _, remainingWeightedTrees@_*) =>
        remainingWeightedTrees.map { wd =>
          formatDTree(wd.tree, tree :+ s"${wd.weight} * ${path.show}", searchPath)
        }.filter(!_.isEmpty).head
      case Alt(path, _, remainingTrees@_*) =>
        remainingTrees.map { d =>
          formatDTree(d, tree :+ path.show, searchPath)
        }.filter(!_.isEmpty).head
      case _ => List.empty
    }
  }

  private[this] def getRouterCtx(prevResp: Response) = {

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
      case Some(addrSet) => addrSet.name.id.asInstanceOf[Path]
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
      case (Some(dTree), Some(addrSet)) =>
        val resp = Response()
        val tree = formatDTree(dTree, List.empty, clientPath)
        resp.contentType
        resp.contentString = Seq(
          prevResp.contentString,
          evaluatedRequest(
            label,
            serviceName.show,
            clientPath.show,
            selectedEndpoint,
            Some(addrSet),
            tree
          )
        ).mkString("\n").trim
        resp
      case (_, _) => Response(Status.BadRequest)
    }
  }

  override def apply(
    req: Request,
    svc: Service[Request, Response]
  ): Future[Response] = {

    val maxTraceDepth = req.headerMap.get(RequestTracerMaxDepthHeader).map(_.toInt)
    val httpMethod = req.method

    (httpMethod, maxTraceDepth) match {
      case (Method.Trace, Some(0)) =>
        getRouterCtx(Response())
      case (Method.Trace, Some(num)) if num > 0 =>
        req.headerMap.set(RequestTracerMaxDepthHeader, (num - 1).toString)
        svc(req).flatMap(getRouterCtx).ensure {
          req.headerMap.set(RequestTracerMaxDepthHeader, num.toString); ()
        }
      case (_, _) => svc(req)
    }
  }
}

object RequestEvaluator {

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
        new RequestEvaluator(endpoint, interpreter, dtab, label.label).andThen(next)
    }
}

