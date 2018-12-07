package io.buoyant.router

import com.twitter.finagle.{Mux => FinagleMux, _}
import com.twitter.finagle.buoyant._
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.mux.{Request, Response}
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.server.StackServer
import com.twitter.util._
import io.buoyant.router.RoutingFactory.{RequestIdentification, IdentifiedRequest}
import java.net.SocketAddress

object Mux extends Router[Request, Response] with Server[Request, Response] {

  object Router {
    val pathStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.newPathStack[Request, Response]

    val boundStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.newBoundStack[Request, Response]
        .replace(MuxEncodeResidual.role, MuxEncodeResidual)

    val client: StackClient[Request, Response] =
      FinagleMux.client
        .withStack(StackRouter.Client.mkStack(_))

    val defaultParams: Stack.Params =
      StackRouter.defaultParams +
        ProtocolLibrary("mux")

    class Identifier(
      prefix: Path = Path.empty,
      dtab: () => Dtab = () => Dtab.base
    ) extends RoutingFactory.Identifier[Request] {
      def apply(req: Request): Future[RequestIdentification[Request]] = {
        val dst = Dst.Path(prefix ++ req.destination, dtab(), Dtab.local)
        Future.value(new IdentifiedRequest(dst, req))
      }
    }

  }

  case class Router(
    pathStack: Stack[ServiceFactory[Request, Response]] = Router.pathStack,
    boundStack: Stack[ServiceFactory[Request, Response]] = Router.boundStack,
    client: StackClient[Request, Response] = Router.client,
    params: Stack.Params = Router.defaultParams
  ) extends StdStackRouter[Request, Response, Router] {
    protected def copy1(
      pathStack: Stack[ServiceFactory[Request, Response]] = this.pathStack,
      boundStack: Stack[ServiceFactory[Request, Response]] = this.boundStack,
      client: StackClient[Request, Response] = this.client,
      params: Stack.Params = this.params
    ): Router = copy(pathStack, boundStack, client, params)

    protected def newIdentifier(): RoutingFactory.Identifier[Request] = {
      val RoutingFactory.DstPrefix(pfx) = params[RoutingFactory.DstPrefix]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]
      new Router.Identifier(pfx, baseDtab)
    }
  }

  val router = Router()
  def factory(): ServiceFactory[Request, Response] =
    router.factory()

  object Server {
    val stack: Stack[ServiceFactory[Request, Response]] =
      FinagleMux.server.stack

    val defaultParams: Stack.Params =
      StackServer.defaultParams +
        ProtocolLibrary("mux")
  }

  val server = FinagleMux.Server(Server.stack, Server.defaultParams)

  def serve(
    addr: SocketAddress,
    factory: ServiceFactory[Request, Response]
  ): ListeningServer = server.serve(addr, factory)

}
