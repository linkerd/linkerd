package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant.http2._
import com.twitter.finagle.param
import com.twitter.finagle.server.{Listener, StackServer, StdStackServer}
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Closable, Future}
import io.netty.handler.codec.http2.Http2StreamFrame
import java.net.SocketAddress

object Http2 extends Server[Request, Response] {

  case class Server(
    stack: Stack[ServiceFactory[Request, Response]] = StackServer.newStack,
    params: Stack.Params = StackServer.defaultParams + param.ProtocolLibrary("h2")
  ) extends StdStackServer[Request, Response, Server] {

    protected type In = Http2StreamFrame
    protected type Out = Http2StreamFrame

    protected def newListener(): Listener[Http2StreamFrame, Http2StreamFrame] =
      Http2Listener.mk(params)

    protected def newDispatcher(
      transport: Transport[In, Out],
      service: Service[Request, Response]
    ): Closable = {
      val stream = new ServerStreamTransport(transport)
      val pending = stream.read().flatMap { req =>
        service(req).flatMap(stream.write(_)).flatten
      }
      Closable.make { deadline =>
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
