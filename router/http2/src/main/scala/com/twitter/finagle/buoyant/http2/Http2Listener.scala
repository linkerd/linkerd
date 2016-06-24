package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.server.Listener
import io.netty.channel._
import io.netty.handler.codec.http2.{Http2Frame, Http2MultiplexCodec}
import io.netty.channel.socket.SocketChannel

/**
 * Please note that the listener cannot be used for TLS yet.
 */
object Http2Listener {

  def apply(params: Stack.Params): Listener[Http2Frame, Http2Frame] = Netty4Listener(
    pipelineInit = pipelineInit,
    // we turn off backpressure because Http2 only works with autoread on for now
    params = params + Netty4Listener.BackPressure(false),
    handlerDecorator = mkHttp2(params)
  )

  private[this] def mkHttp2(params: Stack.Params): ChannelInitializer[Channel] => ChannelHandler =
    init => new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel): Unit = {
        val _ = ch.pipeline.addLast(new Http2MultiplexCodec(true, init))
      }
    }

  private[this] val log = com.twitter.logging.Logger.get()

  private[this] val pipelineInit: ChannelPipeline => Unit = { pipeline: ChannelPipeline =>
    pipeline.addLast(new Debug(s"Http2Listener[http2:0]"))
    // pipeline.addLast(new Http2ServerDowngrader(false /*validateHeaders*/))
    pipeline.addLast(new Debug(s"Http2Listener[http2:1]"))
    log.fatal(s"Http2Listener.pipeline: $pipeline")
  }

  private[this] class Debug(prefix: String) extends SimpleChannelInboundHandler[Http2Frame] {
    override def toString() = prefix
    override def channelActive(ctx: ChannelHandlerContext): Unit =
      log.info(s"$prefix.channelActive $ctx")
    override def channelInactive(ctx: ChannelHandlerContext): Unit =
      log.info(s"$prefix.channelInactive $ctx")
    override def channelRead0(ctx: ChannelHandlerContext, obj: Http2Frame): Unit =
      log.info(s"$prefix.channelRead0 $ctx $obj")
    override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
      log.info(s"$prefix.channelReadComplete $ctx")
  }
}
