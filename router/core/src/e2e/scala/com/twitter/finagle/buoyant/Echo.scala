package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.client.{StackClient, StdStackClient, Transporter}
import com.twitter.finagle.dispatch.{SerialClientDispatcher, SerialServerDispatcher}
import com.twitter.finagle.netty4.{Netty4Listener, Netty4Transporter}
import com.twitter.finagle.server.{StackServer, StdStackServer}
import com.twitter.finagle.transport.{Transport, TransportContext}
import com.twitter.io.Charsets
import com.twitter.util.Future
import java.net.SocketAddress
import java.nio.charset.StandardCharsets.UTF_8

import com.twitter.finagle.stats.NullStatsReceiver
import io.netty.channel._
import io.netty.handler.codec.{DelimiterBasedFrameDecoder, Delimiters}
import io.netty.handler.codec.string.{StringDecoder, StringEncoder}

/**
 * Lovingly stolen from finagle-core's tests
 *
 * Copyright 2015 Twitter Inc and all that jazz.
 */
object Echo extends Client[String, String] with Server[String, String] {

  def newClient(dest: Name, label: String) =
    client.newClient(dest, label)

  def newService(dest: Name, label: String) =
    client.newService(dest, label)

  def serve(addr: SocketAddress, service: ServiceFactory[String, String]) =
    server.serve(addr, service)

  /*
   * Finagle Client
   */

  private class DelimEncoder(delim: Char) extends ChannelOutboundHandlerAdapter {
    override def write(ctx: ChannelHandlerContext, msg: Any, p: ChannelPromise): Unit = {
      val delimMsg = msg match {
        case m: String => m + delim
        case m => m
      }

      ctx.write(delimMsg, p)
      ()
    }
  }

  private object StringClientPipeline extends (ChannelPipeline => Unit) {
    def apply(pipeline: ChannelPipeline): Unit = {
      pipeline.addLast("stringEncode", new StringEncoder(UTF_8))
      pipeline.addLast("stringDecode", new StringDecoder(UTF_8))
      pipeline.addLast("line", new DelimEncoder('\n'))
      ()
    }
  }

  case class RichClient(underlying: Service[String, String]) {
    def ping(): Future[String] = underlying("ping")
  }

  trait StringRichClient { self: com.twitter.finagle.Client[String, String] =>
    def newRichClient(dest: Name, label: String): RichClient =
      RichClient(newService(dest, label))
  }

  case class Client(
    stack: Stack[ServiceFactory[String, String]] = StackClient.newStack,
    params: Stack.Params = Stack.Params.empty
  )
    extends StdStackClient[String, String, Client]
    with StringRichClient { self =>
    protected def copy1(
      stack: Stack[ServiceFactory[String, String]] = this.stack,
      params: Stack.Params = this.params
    ): Client = copy(stack, params)

    protected type In = String
    protected type Out = String
    protected type Context = TransportContext

    protected def newTransporter(addr: SocketAddress): Transporter[String, String, TransportContext] =
      Netty4Transporter.raw(StringClientPipeline, addr, params)

    protected def newDispatcher(transport: Transport[super.In, super.Out] {
      type Context <: self.Context
    }) =
      new SerialClientDispatcher(transport, NullStatsReceiver)
  }

  val client = Client()


  /*
   * Finagle Server
   */

  object StringServerPipeline extends (ChannelPipeline => Unit) {
    def apply(pipeline: ChannelPipeline): Unit = {
      pipeline.addLast("line", new DelimiterBasedFrameDecoder(100, Delimiters.lineDelimiter: _*))
      pipeline.addLast("stringDecoder", new StringDecoder(UTF_8))
      pipeline.addLast("stringEncoder", new StringEncoder(UTF_8))
      ()
    }
  }

  case class Server(
    stack: Stack[ServiceFactory[String, String]] = StackServer.newStack,
    params: Stack.Params = StackServer.defaultParams
  ) extends StdStackServer[String, String, Server] { self =>
    protected def copy1(
      stack: Stack[ServiceFactory[String, String]] = this.stack,
      params: Stack.Params = this.params
    ) = copy(stack, params)

    protected type In = String
    protected type Out = String
    protected type Context = TransportContext

    protected def newListener() = Netty4Listener(StringServerPipeline, params)
    protected def newDispatcher(transport: Transport[String, String] {
      type Context <: self.Context
    }, service: Service[String, String]) =
      new SerialServerDispatcher(transport, service)
  }

  val server = Server()
}
