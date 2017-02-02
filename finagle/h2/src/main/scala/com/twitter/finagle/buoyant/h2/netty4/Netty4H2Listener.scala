package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.netty4.channel.DirectToHeapInboundHandler
import com.twitter.finagle.server.Listener
import com.twitter.finagle.transport.{TlsConfig, Transport}
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http2._
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
      p.addLast(DirectToHeapInboundHandler)
      p.addLast(new ServerUpgradeHandler); ()
    }
  }

  private[this] object TlsListener extends ListenerMaker {
    val PlaceholderKey = "h2 framer placeholder"
    override protected[this] val pipelineInit = { p: ChannelPipeline =>
      p.addLast(DirectToHeapInboundHandler)
      p.addLast(PlaceholderKey, new ChannelDuplexHandler)
        .addLast("alpn", new Alpn); ()
    }

    private class Alpn
      extends ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_2) {

      override protected def configurePipeline(ctx: ChannelHandlerContext, proto: String): Unit =
        proto match {
          case ApplicationProtocolNames.HTTP_2 =>
            ctx.channel.config.setAutoRead(true)

            // TODO configure settings from params
            val codec = H2FrameCodec.server()
            ctx.pipeline.replace(PlaceholderKey, "h2 framer", codec); ()

          // TODO case ApplicationProtocolNames.HTTP_1_1 =>
          case proto => throw new IllegalStateException(s"unknown protocol: $proto")
        }
    }
  }

}
