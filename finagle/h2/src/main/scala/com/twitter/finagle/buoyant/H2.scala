package com.twitter.finagle.buoyant

import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.buoyant.h2.netty4._
import com.twitter.finagle.client.{StackClient, StdStackClient, Transporter}
import com.twitter.finagle.param.{WithDefaultLoadBalancer, WithSessionPool}
import com.twitter.finagle.pool.SingletonPool
import com.twitter.finagle.server.{Listener, StackServer, StdStackServer}
import com.twitter.finagle.stack.nilStack
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{param, _}
import com.twitter.util.Closable
import io.netty.handler.codec.http2.Http2Frame
import java.net.SocketAddress

object H2 extends Client[Request, Response] with Server[Request, Response] {

  private[this]type Http2FrameTransporter = Transporter[Http2Frame, Http2Frame]
  private[this]type Http2FrameTransport = Transport[Http2Frame, Http2Frame]

  object Client {

    val newStack: Stack[ServiceFactory[Request, Response]] = {
      // Advertise H2 support
      val stk = new StackBuilder(nilStack[Request, Response])
      stk.push(H2ApplicationProtocol.module)
      stk.push(TracingFilter.module)
      (StackClient.newStack[Request, Response] ++ stk.result)
        .replace(StackClient.Role.pool, SingletonPool.module[Request, Response])
        .replace(StackClient.Role.prepConn, DelayedRelease.module)
    }

    val defaultParams = StackClient.defaultParams +
      param.ProtocolLibrary("h2")
  }

  case class Client(
    stack: Stack[ServiceFactory[Request, Response]] = Client.newStack,
    params: Stack.Params = Client.defaultParams
  ) extends StdStackClient[Request, Response, Client]
    with WithSessionPool[Client]
    with WithDefaultLoadBalancer[Client] {

    protected type In = Http2Frame
    protected type Out = Http2Frame

    protected def newTransporter(addr: SocketAddress): Http2FrameTransporter =
      Netty4H2Transporter.mk(addr, params)

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

  object Server {
    val newStack: Stack[ServiceFactory[Request, Response]] = StackServer.newStack

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
