package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2.{Request, Response, Reset}
import com.twitter.finagle.buoyant.h2.netty4._
import com.twitter.finagle.buoyant.h2.param._
import com.twitter.finagle.param
import com.twitter.finagle.client.{StackClient, StdStackClient, Transporter}
import com.twitter.finagle.server.{Listener, StackServer, StdStackServer}
import com.twitter.finagle.service.StatsFilter
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Closable, Future}
import io.netty.handler.codec.http2.Http2Frame
import java.net.SocketAddress

object H2 extends Client[Request, Response]
  with Router[Request, Response]
  with Server[Request, Response] {

  private[this] val log = com.twitter.logging.Logger.get(getClass.getName)

  private[this]type Http2FrameTransporter = Transporter[Http2Frame, Http2Frame]
  private[this]type Http2FrameTransport = Transport[Http2Frame, Http2Frame]

  /**
   * Clients and servers may accumulate data frames into a single
   * combined data frame.
   */

  case class Identifier(mk: Stack.Params => RoutingFactory.Identifier[Request])
  implicit private[buoyant] object Identifier extends Stack.Param[Identifier] {
    private[this] val nilF =
      Future.value(new RoutingFactory.UnidentifiedRequest[Request]("no request identifier"))
    private[this] val nil = (_req: Request) => nilF
    val default = Identifier(params => nil)
  }

  /*
   * Client
   */

  object Client {
    val newStack: Stack[ServiceFactory[Request, Response]] =
      StackClient.newStack
        .insertAfter(StatsFilter.role, h2.StreamStatsFilter.module)

    val defaultParams = StackClient.defaultParams +
      param.ProtocolLibrary("h2")
  }

  case class Client(
    stack: Stack[ServiceFactory[Request, Response]] = Client.newStack,
    params: Stack.Params = Client.defaultParams
  ) extends StdStackClient[Request, Response, Client] {

    protected type In = Http2Frame
    protected type Out = Http2Frame

    protected def newTransporter(): Http2FrameTransporter =
      Netty4H2Transporter.mk(params)

    protected def copy1(
      stack: Stack[ServiceFactory[Request, Response]] = this.stack,
      params: Stack.Params = this.params
    ): Client = copy(stack, params)

    private[this] lazy val param.Stats(statsReceiver) = params[param.Stats]
    private[this] lazy val streamStats =
      new Netty4StreamTransport.StatsReceiver(statsReceiver.scope("stream"))

    protected def newDispatcher(trans: Http2FrameTransport): Service[Request, Response] =
      new Netty4ClientDispatcher(trans, streamStats)
  }

  val client = Client()

  def newService(dest: Name, label: String): Service[Request, Response] =
    client.newService(dest, label)

  def newClient(dest: Name, label: String): ServiceFactory[Request, Response] =
    client.newClient(dest, label)

  /*
   * Router
   */

  object Router {
    val pathStack: Stack[ServiceFactory[Request, Response]] = {
      val stk = StackRouter.newPathStack
        .insertAfter(StatsFilter.role, h2.StreamStatsFilter.module)
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

  /*
   * Server
   */

  object Server {
    val newStack: Stack[ServiceFactory[Request, Response]] = StackServer.newStack
      .insertAfter(StackServer.Role.protoTracing, h2.ProxyRewriteFilter.module)
      .insertAfter(StatsFilter.role, h2.StreamStatsFilter.module)

    val defaultParams = StackServer.defaultParams +
      param.ProtocolLibrary("h2")
  }

  case class Server(
    stack: Stack[ServiceFactory[Request, Response]] = Server.newStack,
    params: Stack.Params = Server.defaultParams
  ) extends StdStackServer[Request, Response, Server] {

    protected type In = Http2Frame
    protected type Out = Http2Frame

    protected def copy1(
      stack: Stack[ServiceFactory[Request, Response]] = this.stack,
      params: Stack.Params = this.params
    ): Server = copy(stack, params)

    /**
     * Creates a Listener that creates a new Transport for each
     * incoming HTTP/2 *stream*.
     */
    protected def newListener(): Listener[Http2Frame, Http2Frame] =
      Netty4H2Listener.mk(params)

    private[this] lazy val statsReceiver = params[param.Stats].statsReceiver
    private[this] lazy val streamStats =
      new Netty4StreamTransport.StatsReceiver(statsReceiver.scope("stream"))

    /** A dispatcher is created for each inbound HTTP/2 connection. */
    protected def newDispatcher(
      trans: Http2FrameTransport,
      service: Service[Request, Response]
    ): Closable = {
      new Netty4ServerDispatcher(trans, service, streamStats)
    }
  }

  val server = Server()

  def serve(addr: SocketAddress, service: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, service)
}
