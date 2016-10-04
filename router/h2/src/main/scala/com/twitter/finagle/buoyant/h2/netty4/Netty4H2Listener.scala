package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.server.Listener
import com.twitter.finagle.transport.{TlsConfig, Transport}
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http2.{Http2Codec, Http2StreamFrame}
import io.netty.handler.ssl.{ApplicationProtocolNames, ApplicationProtocolNegotiationHandler}

/**
 * Based on com.twitter.finagle.http2.Http2Listener
 */
object Netty4H2Listener {
  private val log = com.twitter.logging.Logger.get(getClass.getName)

  def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] =
    params[Transport.Tls] match {
      case Transport.Tls(TlsConfig.Disabled) =>
        params[param.PriorKnowledge] match {
          case param.PriorKnowledge(false) =>
            throw new IllegalArgumentException("param.PriorKnowledge must be true")
          case param.PriorKnowledge(true) =>
            log.info("prior knowledge listener")
            PriorKnowledgeListener.mk(params)
        }
      case _ =>
        log.info("tls listener")
        TlsListener.mk(params)
    }

  val CodecKey = "h2 codec"

  val CodecPlaceholderKey = "h2 codec placeholder"
  protected[this] val CodecPlaceholderInit: ChannelPipeline => Unit = { p =>
    p.addLast(CodecPlaceholderKey, new ChannelDuplexHandler); ()
  }

  private[this] def streamInitializer(init: ChannelInitializer[Channel]): ChannelHandler =
    new ChannelInitializer[Channel] {
      def initChannel(ch: Channel): Unit = {
        ch.pipeline.addLast(init); ()
        // ch.pipeline.addLast(new DebugHandler("s.frame")); ()
      }
    }

  private[this] def mkCodec(init: ChannelInitializer[Channel]) =
    new Http2Codec(true /* server */ , streamInitializer(init))

  private[this] trait ListenerMaker {
    def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] =
      Netty4Listener(
        pipelineInit = pipelineInit,
        params = params + Netty4Listener.BackPressure(false),
        setupMarshalling = setupMarshalling
      )

    protected[this] def pipelineInit: ChannelPipeline => Unit
    protected[this] def setupMarshalling: ChannelInitializer[Channel] => ChannelHandler
  }

  private[this] object PriorKnowledgeListener extends ListenerMaker {
    override protected[this] val pipelineInit = CodecPlaceholderInit
    override protected[this] val setupMarshalling = { init: ChannelInitializer[Channel] =>
      val codec = mkCodec(init)
      new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel): Unit = {
          // ch.pipeline.addLast(new DebugHandler("s.bytes"))
          ch.pipeline.replace(CodecPlaceholderKey, CodecKey, codec); ()
        }
      }
    }
  }

  private[this] object TlsListener extends ListenerMaker {
    override protected[this] val pipelineInit = { p: ChannelPipeline =>
      println(p)
      // p.replace("tls init", "h2 tls init", )
      CodecPlaceholderInit(p)
    }
    override protected[this] val setupMarshalling = { init: ChannelInitializer[Channel] =>
      val alpn = new Alpn(mkCodec(init))
      new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel): Unit = {
          ch.pipeline.addLast(alpn); ()
        }
      }
    }

    class Alpn(codec: ChannelHandler)
      extends ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_2) {

      override protected def configurePipeline(ctx: ChannelHandlerContext, proto: String): Unit =
        proto match {
          case ApplicationProtocolNames.HTTP_2 =>
            ctx.channel.config.setAutoRead(true) // xxx cargo cult
            ctx.pipeline.replace(CodecPlaceholderKey, CodecKey, codec); ()

          // TODO case ApplicationProtocolNames.HTTP_1_1 =>
          case proto => throw new IllegalStateException(s"unknown protocol: $proto")
        }
    }
  }

}
