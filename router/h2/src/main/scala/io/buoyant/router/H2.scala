package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2.{Request, Response, ServerDispatcher}
import com.twitter.finagle.buoyant.h2.netty4._
import com.twitter.finagle.param
import com.twitter.finagle.client.{StackClient, StdStackClient, Transporter}
import com.twitter.finagle.server.{Listener, StackServer, StdStackServer}
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Closable, Future}
import io.buoyant.router.h2.PathIdentifier
import io.netty.handler.codec.http2.Http2StreamFrame
import java.net.SocketAddress

object H2 extends Client[Request, Response]
  with Router[Request, Response]
  with Server[Request, Response] {

  private[this] val log = com.twitter.logging.Logger.get(getClass.getName)

  private[this]type Http2StreamFrameTransporter = Transporter[Http2StreamFrame, Http2StreamFrame]
  private[this]type Http2StreamFrameTransport = Transport[Http2StreamFrame, Http2StreamFrame]

  /**
   * Clients and servers may accumulate data frames into a single
   * combined data frame.
   */
  private[buoyant] case class MinAccumFrames(count: Int)
  implicit private[buoyant] object MinAccumFrames extends Stack.Param[MinAccumFrames] {
    val default = MinAccumFrames(Int.MaxValue)
  }

  case class Identifier(mk: Stack.Params => RoutingFactory.Identifier[Request])
  implicit private[buoyant] object Identifier extends Stack.Param[Identifier] {
    val default = PathIdentifier.param
  }

  case class PriorKnowledge(enabled: Boolean)
  implicit object PriorKnowledge extends Stack.Param[PriorKnowledge] {
    val default = PriorKnowledge(true)
  }

  /*
   * Client
   */

  object Client {
    val newStack: Stack[ServiceFactory[Request, Response]] =
      StackClient.newStack

    val defaultParams = StackClient.defaultParams +
      param.ProtocolLibrary("h2")
  }

  case class Client(
    stack: Stack[ServiceFactory[Request, Response]] = Client.newStack,
    params: Stack.Params = Client.defaultParams
  ) extends StdStackClient[Request, Response, Client] {

    protected type In = Http2StreamFrame
    protected type Out = Http2StreamFrame

    protected def newTransporter(): Http2StreamFrameTransporter =
      Netty4H2Transporter.mk(params)

    protected def copy1(
      stack: Stack[ServiceFactory[Request, Response]] = this.stack,
      params: Stack.Params = this.params
    ): Client = copy(stack, params)

    private[this] lazy val param.Stats(statsReceiver) = params[param.Stats]
    private[this] lazy val dispatchStats = statsReceiver.scope("dispatch")

    protected def newDispatcher(trans: Http2StreamFrameTransport): Service[Request, Response] =
      new Netty4ClientDispatcher(
        trans,
        minAccumFrames = params[MinAccumFrames].count,
        statsReceiver = dispatchStats
      )
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
    val pathStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.newPathStack

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

    val defaultParams = StackServer.defaultParams +
      param.ProtocolLibrary("h2")
  }

  case class Server(
    stack: Stack[ServiceFactory[Request, Response]] = StackServer.newStack,
    params: Stack.Params = Server.defaultParams
  ) extends StdStackServer[Request, Response, Server] {

    protected type In = Http2StreamFrame
    protected type Out = Http2StreamFrame

    protected def copy1(
      stack: Stack[ServiceFactory[Request, Response]] = this.stack,
      params: Stack.Params = this.params
    ): Server = copy(stack, params)

    /**
     * Creates a Listener that creates a new Transport for each
     * incoming HTTP/2 *stream*.
     */
    protected def newListener(): Listener[Http2StreamFrame, Http2StreamFrame] =
      Netty4H2Listener.mk(params)

    private[this] lazy val statsReceiver = params[param.Stats].statsReceiver
    private[this] lazy val dispatchStats = statsReceiver.scope("dispatch")
    private[this] lazy val transportStats = statsReceiver.scope("transport")

    /** A dispatcher is created for each outbound HTTP/2 stream. */
    protected def newDispatcher(
      trans: Http2StreamFrameTransport,
      service: Service[Request, Response]
    ): Closable = {
      val stream = new Netty4ServerStreamTransport(trans, params[MinAccumFrames].count, transportStats)
      new ServerDispatcher(stream, service, dispatchStats)
    }
  }

  val server = Server()

  def serve(addr: SocketAddress, service: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, service)
}
