package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util.Future
import io.buoyant.admin.DelegationJsonCodec
import io.buoyant.admin.names.DelegateApiHandler.JsonDelegateTree
import io.buoyant.config.Parser
import io.buoyant.namer.{ConfiguredNamersInterpreter, Delegator}
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}

case class EvaluatedRequest(
  identification: String,
  selectedAddress: String,
  addresses: Option[Set[String]],
  dtabResolution: Option[JsonDelegateTree]
)

class RequestEvaluator(
  endpoint: EndpointAddr,
  namer: DstBindingFactory.Namer,
  dtab: BaseDtab
) extends SimpleFilter[Request, Response] {
  private val jsonMapper = Parser.jsonObjectMapper(Nil).registerModule(DelegationJsonCodec.mkModule())
  private val evaluatorHeaderName = "l5d-req-evaluate"
  private val JsonCharSet = ";charset=UTF-8"

  override def apply(
    req: Request,
    svc: Service[Request, Response]
  ): Future[Response] = {

    req.headerMap.get(evaluatorHeaderName) match {
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
            .flatMap(JsonDelegateTree.mk).map(Some(_))
          case _ => Future.None
        }
        dtreeF.map { dtree =>
          val resp = Response()
          resp.contentType = MediaType.Json + JsonCharSet
          resp.contentString = jsonMapper
            .writeValueAsString(
              EvaluatedRequest(
                identificationPath.show,
                selectedEndpoint,
                addresses,
                dtree
              )
            )
          resp
        }

    }
  }
}

object RequestEvaluator {
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
}

