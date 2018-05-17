package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util.{Future, Return, Throw, Try}
import io.buoyant.admin.DelegationJsonCodec
import io.buoyant.config.Parser
import io.buoyant.namer.DelegateTree._
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}

case class EvaluatedRequest(
  identification: String,
  selectedAddress: String,
  addresses: Option[Set[String]],
  dtabResolution: List[String]
) {
  override def toString() = {
    s"""
                                 |identification: $identification
                                 |selectedAddress: $selectedAddress
                                 |addresses: ${addresses.getOrElse(Set.empty).mkString(",")}
                                 |Dtab Resolution:
                                 |${dtabResolution.mkString("\n")}
    """.stripMargin
  }
}

class RequestEvaluator(
  endpoint: EndpointAddr,
  namer: DstBindingFactory.Namer,
  dtab: BaseDtab
) extends SimpleFilter[Request, Response] {

  private val EvaluatorHeaderName = "l5d-req-evaluate"
  private val JsonCharSet = ";charset=UTF-8"

  override def apply(
    req: Request,
    svc: Service[Request, Response]
  ): Future[Response] = {

    req.headerMap.get(EvaluatorHeaderName) match {
      case None => svc(req)
      case Some(_) =>

        val identificationPath = DstPathCtx.current match {
          case Some(dstPath) => dstPath.path
          case None => Path.empty
        }

        val selectedEndpoint = endpoint.addr match {
          case inetAddr: Address.Inet => inetAddr.addr.toString
          case _ => ""
        }

        val lbSet = DstBoundCtx.current match {
          case None => Addr.Neg
          case Some(addrSet) => addrSet.name.addr.sample()
        }

        val addresses = lbSet match {
          case Addr.Neg | Addr.Failed(_) | Addr.Pending => None
          case Addr.Bound(a, _) => Some(
            a.map { address: Address =>
              address match {
                case inetAddr: Address.Inet => inetAddr.addr.toString
                case _ => ""
              }
            }
          )
        }

        val dtreeF = namer.interpreter match {
          case delegator: Delegator => delegator.delegate(dtab.dtab(), identificationPath)
            .map(Some(_))
          case _ => Future.None
        }

        dtreeF.map { dtree =>
          val resp = Response()
          val tree = RequestEvaluator.formatDTree(dtree, req.contentType, List.empty)
          val evaluatedRequest = EvaluatedRequest(
            Try(tree.last) match {
              case Throw(_) => "Unknown identification"
              case Return(id) => id
            },
            selectedEndpoint,
            addresses,
            tree
          )

          resp.contentType = req.contentType.getOrElse(MediaType.PlainText)
          resp.contentString = RequestEvaluator
            .writeContentString(resp.contentType, evaluatedRequest)
          resp
        }
    }
  }
}

object RequestEvaluator {

  private val jsonMapper = Parser.jsonObjectMapper(Nil)
    .registerModule(DelegationJsonCodec.mkModule())

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module3[EndpointAddr, RoutingFactory.BaseDtab, DstBindingFactory.Namer, ServiceFactory[Request, Response]] {

      override def role: Stack.Role = Stack.Role("RequestEvaluator")

      override def description: String = "Intercepts to respond with useful client destination info"

      override def make(
        endpoint: EndpointAddr,
        dtab: BaseDtab,
        interpreter: DstBindingFactory.Namer,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] = new RequestEvaluator(endpoint, interpreter, dtab)
        .andThen(next)
    }

  def formatDTree(
    dTree: Option[DelegateTree[Name.Bound]],
    output: Any,
    tree: List[String]
  ): List[String] = {
    dTree match {
      case None =>
        tree
      case Some(_: Empty) | Some(_: Fail) | Some(_: Exception) | Some(Neg(_, _)) =>
        List.empty

      case Some(Leaf(path, dentry, value)) =>
        tree :+ path.show

      case Some(Transformation(path, name, value, remainingTree)) =>
        formatDTree(Some(remainingTree), output, tree :+ value.path.show)

      case Some(Delegate(path, dentry, remainingTree)) =>
        formatDTree(Some(remainingTree), output, tree :+ path.show)

      case Some(Union(path, dentry, remainingWeightedTrees@_*)) =>
        remainingWeightedTrees.map { wd =>
          formatDTree(Some(wd.tree), output, tree :+ s"${wd.weight} * ${path.show}")
        }.filter(!_.isEmpty).head
      case Some(Alt(path, dentry, remainingTrees@_*)) =>
        remainingTrees.map { d =>
          formatDTree(Some(d), output, tree :+ path.show)
        }.filter(!_.isEmpty).head
    }
  }

  def writeContentString(contentType: Option[String], eval: EvaluatedRequest): String =
    contentType match {
      case Some(respContentType) if respContentType == MediaType.Json =>
        jsonMapper.writeValueAsString(eval)
      case _ => eval.toString
    }
}

