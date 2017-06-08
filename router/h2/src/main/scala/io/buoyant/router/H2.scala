package io.buoyant.router

import com.twitter.finagle.buoyant.h2.{Request, Response, ResponseClassifiers}
import com.twitter.finagle.buoyant.{H2 => FinagleH2}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.{param, _}
import com.twitter.finagle.server.StackServer
import com.twitter.util.Future
import java.net.SocketAddress

object H2 extends Router[Request, Response]
  with Client[Request, Response]
  with Server[Request, Response] {

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
      h2.ViaHeaderFilter.module +: h2.ClassifierFilter.module +: stk
    }

    val boundStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.newBoundStack

    val clientStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.Client.mkStack(FinagleH2.Client.newStack)

    val defaultParams = StackRouter.defaultParams +
      param.ProtocolLibrary("h2")
  }

  case class Router(
    pathStack: Stack[ServiceFactory[Request, Response]] = Router.pathStack,
    boundStack: Stack[ServiceFactory[Request, Response]] = Router.boundStack,
    client: StackClient[Request, Response] = FinagleH2.Client(Router.clientStack),
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

  val client = FinagleH2.client

  def newService(dest: Name, label: String): Service[Request, Response] =
    client.newService(dest, label)

  def newClient(dest: Name, label: String): ServiceFactory[Request, Response] =
    client.newClient(dest, label)

  object Server {
    val newStack: Stack[ServiceFactory[Request, Response]] = FinagleH2.Server.newStack
      .insertAfter(StackServer.Role.protoTracing, h2.ProxyRewriteFilter.module)

    private val serverResponseClassifier = ClassifiedRetries.orElse(
      h2.ClassifierFilter.successClassClassifier,
      ResponseClassifiers.NonRetryableServerFailures
    )
    val defaultParams = StackServer.defaultParams + param.ResponseClassifier(serverResponseClassifier)
  }

  val server = FinagleH2.Server(Server.newStack, Server.defaultParams)

  def serve(addr: SocketAddress, service: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, service)

}
