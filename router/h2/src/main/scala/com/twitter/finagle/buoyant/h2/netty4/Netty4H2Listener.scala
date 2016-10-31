package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.server.Listener
import com.twitter.finagle.transport.{TlsConfig, Transport}
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http2.{Http2FrameCodec, Http2Frame}
import io.netty.handler.ssl.{ApplicationProtocolNames, ApplicationProtocolNegotiationHandler}

/**
 * Based on com.twitter.finagle.http2.Http2Listener
 */
object Netty4H2Listener {
  private val log = com.twitter.logging.Logger.get(getClass.getName)

  def mk(params: Stack.Params): Listener[Http2Frame, Http2Frame] =
    params[Transport.Tls] match {
      case Transport.Tls(TlsConfig.Disabled) => PlaintextListener.mk(params)
      case _ => TlsListener.mk(params)
    }

  def mkCodec(framer: Http2FrameCodec): ChannelHandler = new ChannelInitializer[Channel] {
    def initChannel(ch: Channel): Unit = {
      // ch.pipeline.addLast("debug bytes", new DebugHandler("s.bytes"))
      ch.pipeline.addLast("h2 codec", framer); ()
      // ch.pipeline.addLast("debug frames", new DebugHandler("s.framed"))
    }
  }

  private[this] trait ListenerMaker {
    def mk(params: Stack.Params): Listener[Http2Frame, Http2Frame] =
      Netty4Listener(
        pipelineInit = pipelineInit,
        params = params + Netty4Listener.BackPressure(false)
      )

    protected[this] def pipelineInit: ChannelPipeline => Unit
  }

  private[this] object PlaintextListener extends ListenerMaker {
    override protected[this] val pipelineInit = { p: ChannelPipeline =>
      p.addLast(mkCodec(new Http2FrameCodec(true))); ()
    }
  }

  private[this] object TlsListener extends ListenerMaker {
    override protected[this] val pipelineInit = { p: ChannelPipeline =>
      p.addLast("alpn", new Alpn(mkCodec(new Http2FrameCodec(true)))); ()
    }

    private class Alpn(codec: ChannelHandler)
      extends ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_2) {

      override protected def configurePipeline(ctx: ChannelHandlerContext, proto: String): Unit =
        proto match {
          case ApplicationProtocolNames.HTTP_2 =>
            ctx.channel.config.setAutoRead(true)
            ctx.pipeline.addLast(codec); ()

          // TODO case ApplicationProtocolNames.HTTP_1_1 =>
          case proto => throw new IllegalStateException(s"unknown protocol: $proto")
        }
    }
  }

}
