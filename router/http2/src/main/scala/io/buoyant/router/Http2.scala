package io.buoyant.router

import com.twitter.finagle._
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

  case class Client(
    stack: Stack[ServiceFactory[Request, Response]] = StackClient.newStack,
    params: Stack.Params = StackClient.defaultParams + param.ProtocolLibrary("h2")
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

  case class Server(
    stack: Stack[ServiceFactory[Request, Response]] = StackServer.newStack,
    params: Stack.Params = StackServer.defaultParams + param.ProtocolLibrary("h2")
  ) extends StdStackServer[Request, Response, Server] {

    protected type In = Http2StreamFrame
    protected type Out = Http2StreamFrame

    protected def newListener(): Listener[Http2StreamFrame, Http2StreamFrame] =
      Http2Listener.mk(params)

    // XXX YOLO
    protected def newDispatcher(
      transport: Transport[In, Out],
      service: Service[Request, Response]
    ): Closable = {
      val stream = new ServerStreamTransport(transport)

      log.info("h2.srv: stream read")
      val pending = stream.read().flatMap { req =>
        log.info(s"h2.srv: stream req: $req")

        service(req).flatMap { rsp =>
          log.info(s"h2.srv: stream rsp: $rsp")

          stream.write(rsp).flatMap { writing =>
            log.info(s"h2.srv: stream wrote rsp preface")

            writing.respond { t =>
              log.info(s"h2.srv: stream wrote rsp body: $t")
            }
          }
        }
      }

      Closable.make { deadline =>
        log.info(s"h2.srv: close")
        pending.raise(new CancelledRequestException)
        service.close(deadline).join(transport.close(deadline)).unit
      }
    }

    protected def copy1(
      stack: Stack[ServiceFactory[Request, Response]] = this.stack,
      params: Stack.Params = this.params
    ): Server = copy(stack, params)
  }

  val server = Server()

  def serve(addr: SocketAddress, service: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, service)
}
