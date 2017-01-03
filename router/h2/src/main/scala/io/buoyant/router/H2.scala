package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant.{Dst, H2 => FinagleH2}
import com.twitter.finagle.buoyant.h2.{Request, Response, Reset}
import com.twitter.finagle.param
import com.twitter.finagle.client.{StackClient, StdStackClient, Transporter}
import com.twitter.finagle.server.{Listener, StackServer, StdStackServer}
import com.twitter.finagle.service.StatsFilter
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Closable, Future}
import java.net.SocketAddress

object H2 extends Client[Request, Response] with Server[Request, Response]
  with Router[Request, Response] {

  val client = FinagleH2.client

  def newService(dest: Name, label: String): Service[Request, Response] =
    client.newService(dest, label)

  def newClient(dest: Name, label: String): ServiceFactory[Request, Response] =
    client.newClient(dest, label)

  object Server {
    val newStack: Stack[ServiceFactory[Request, Response]] = FinagleH2.Server.newStack
      .insertAfter(StackServer.Role.protoTracing, h2.ProxyRewriteFilter.module)
  }

  val server = FinagleH2.server.withStack(Server.newStack)

  def serve(addr: SocketAddress, service: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, service)

  /*
   * Router
   */

  case class Identifier(mk: Stack.Params => RoutingFactory.Identifier[Request])
  implicit private[buoyant] object Identifier extends Stack.Param[Identifier] {
    private[this] val nilF =
      Future.value(new RoutingFactory.UnidentifiedRequest[Request]("no request identifier"))
    private[this] val nil = (_req: Request) => nilF
    val default = Identifier(params => nil)
  }

  object Router {
    val pathStack: Stack[ServiceFactory[Request, Response]] = {
      val stk = StackRouter.newPathStack[Request, Response]
      h2.ViaHeaderFilter.module +: stk
    }

    val boundStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.newBoundStack

    val clientStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.Client.mkStack(H2.client.stack)

    val defaultParams = StackRouter.defaultParams +
      param.ProtocolLibrary("h2")
  }

  case class Router(
    pathStack: Stack[ServiceFactory[Request, Response]] = Router.pathStack,
    boundStack: Stack[ServiceFactory[Request, Response]] = Router.boundStack,
    client: StackClient[Request, Response] = client.withStack(Router.clientStack),
    params: Stack.Params = Router.defaultParams
  ) extends StdStackRouter[Request, Response, Router] {

    protected def copy1(
      pathStack: Stack[ServiceFactory[Request, Response]] = this.pathStack,
      boundStack: Stack[ServiceFactory[Request, Response]] = this.boundStack,
      client: StackClient[Request, Response] = this.client,
      params: Stack.Params = this.params
    ): Router = copy(pathStack, boundStack, client, params)

    protected def newIdentifier() = params[Identifier].mk(params)
  }

  val router = Router()

  def factory(): ServiceFactory[Request, Response] =
    router.factory()
}
