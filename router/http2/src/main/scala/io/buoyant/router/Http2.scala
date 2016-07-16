package io.buoyant.router

import com.twitter.finagle.{
  CancelledRequestException,
  Client,
  Dtab,
  ListeningServer,
  Name,
  Path,
  Server,
  Service,
  ServiceFactory,
  Stack
}
import com.twitter.finagle.buoyant._
import com.twitter.finagle.buoyant.http2._
import com.twitter.finagle.param
import com.twitter.finagle.client.{StackClient, StdStackClient, Transporter}
import com.twitter.finagle.server.{Listener, StackServer, StdStackServer}
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Closable, Future}
import io.netty.handler.codec.http2.Http2StreamFrame
import java.net.SocketAddress

object Http2 extends Client[Request, Response] with Server[Request, Response] {

  private[this] val log = com.twitter.logging.Logger.get(getClass.getName)

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

    protected def newTransporter(): Transporter[Http2StreamFrame, Http2StreamFrame] =
      Http2Transporter.mk(params)

    protected def copy1(
      stack: Stack[ServiceFactory[Request, Response]] = this.stack,
      params: Stack.Params = this.params
    ): Client = copy(stack, params)

    protected def newDispatcher(
      transport: Transport[Http2StreamFrame, Http2StreamFrame]
    ): Service[Request, Response] = new ClientDispatcher(transport)
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
      StackRouter.Client.mkStack(Http2.client.stack)

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

    protected def newIdentifier() = new RoutingFactory.Identifier[Request] {
      val RoutingFactory.DstPrefix(pfx) = params[RoutingFactory.DstPrefix]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]

      def apply(req: Request): Future[(Dst, Request)] = {
        val dst = Dst.Path(pfx ++ reqPath(req), baseDtab(), Dtab.empty)
        Future.value((dst, req))
      }

      private def reqPath(req: Request): Path = req.headers.path match {
        case "" | "/" => Path.empty
        case UriPath(path) => Path.read(path)
      }

      private object UriPath {
        def unapply(uri: String): Option[String] =
          uri.indexOf('?') match {
            case -1 => Some(uri.stripSuffix("/"))
            case idx => Some(uri.substring(idx + 1).stripSuffix("/"))
          }
      }
    }
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
      Http2Listener.mk(params)

    /**
     * A dispatcher is created for each outbound HTTP/2 stream.
     */
    protected def newDispatcher(
      transport: Transport[Http2StreamFrame, Http2StreamFrame],
      service: Service[Request, Response]
    ): Closable = {
      val stream = new ServerStreamTransport(transport)
      val pending =
        for {
          req <- stream.read()
          rsp <- service(req)
          writing <- stream.write(rsp)
          wrote <- writing
        } yield wrote

      Closable.make { deadline =>
        log.info(s"h2.srv: close")
        pending.raise(new CancelledRequestException)
        service.close(deadline).join(transport.close(deadline)).unit
      }
    }
  }

  val server = Server()

  def serve(addr: SocketAddress, service: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, service)
}
