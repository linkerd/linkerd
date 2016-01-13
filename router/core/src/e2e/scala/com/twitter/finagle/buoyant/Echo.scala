package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.client.{StackClient, StdStackClient, Transporter}
import com.twitter.finagle.dispatch.{SerialClientDispatcher, SerialServerDispatcher}
import com.twitter.finagle.netty3.{Netty3Listener, Netty3Transporter}
import com.twitter.finagle.server.{StackServer, StdStackServer}
import com.twitter.finagle.transport.Transport
import com.twitter.io.Charsets
import com.twitter.util.Future
import java.net.SocketAddress
import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.frame.{DelimiterBasedFrameDecoder, Delimiters}
import org.jboss.netty.handler.codec.string.{StringDecoder, StringEncoder}

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

  private class DelimEncoder(delim: Char) extends SimpleChannelHandler {
    override def writeRequested(ctx: ChannelHandlerContext, evt: MessageEvent) = {
      val newMessage = evt.getMessage match {
        case m: String => m + delim
        case m => m
      }

      Channels.write(ctx, evt.getFuture, newMessage, evt.getRemoteAddress)
    }
  }

  private object StringClientPipeline extends ChannelPipelineFactory {
    def getPipeline = {
      val pipeline = Channels.pipeline()
      pipeline.addLast("stringEncode", new StringEncoder(Charsets.Utf8))
      pipeline.addLast("stringDecode", new StringDecoder(Charsets.Utf8))
      pipeline.addLast("line", new DelimEncoder('\n'))
      pipeline
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
    with StringRichClient {
    protected def copy1(
      stack: Stack[ServiceFactory[String, String]] = this.stack,
      params: Stack.Params = this.params
    ): Client = copy(stack, params)

    protected type In = String
    protected type Out = String

    protected def newTransporter(): Transporter[String, String] =
      Netty3Transporter(StringClientPipeline, params)

    protected def newDispatcher(transport: Transport[In, Out]) =
      new SerialClientDispatcher(transport)
  }

  val client = Client()


  /*
   * Finagle Server
   */

  object StringServerPipeline extends ChannelPipelineFactory {
    def getPipeline = {
      val pipeline = Channels.pipeline()
      pipeline.addLast("line", new DelimiterBasedFrameDecoder(100, Delimiters.lineDelimiter: _*))
      pipeline.addLast("stringDecoder", new StringDecoder(Charsets.Utf8))
      pipeline.addLast("stringEncoder", new StringEncoder(Charsets.Utf8))
      pipeline
    }
  }

  case class Server(
    stack: Stack[ServiceFactory[String, String]] = StackServer.newStack,
    params: Stack.Params = StackServer.defaultParams
  ) extends StdStackServer[String, String, Server] {
    protected def copy1(
      stack: Stack[ServiceFactory[String, String]] = this.stack,
      params: Stack.Params = this.params
    ) = copy(stack, params)

    protected type In = String
    protected type Out = String

    protected def newListener() = Netty3Listener(StringServerPipeline, params)
    protected def newDispatcher(transport: Transport[In, Out], service: Service[String, String]) =
      new SerialServerDispatcher(transport, service)
  }

  val server = Server()
}
