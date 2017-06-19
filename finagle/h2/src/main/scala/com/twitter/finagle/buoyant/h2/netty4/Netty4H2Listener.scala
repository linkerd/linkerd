package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.server.Listener
import com.twitter.finagle.transport.Transport
import io.netty.channel._
import io.netty.handler.codec.http2._
import io.netty.handler.ssl.{ApplicationProtocolNames, ApplicationProtocolNegotiationHandler}

/**
 * Based on com.twitter.finagle.http2.Http2Listener
 */
object Netty4H2Listener {
  private val log = com.twitter.logging.Logger.get(getClass.getName)

  def mk(params: Stack.Params): Listener[Http2Frame, Http2Frame] =
    params[Transport.ClientSsl] match {
      case Transport.ClientSsl(None) => PlaintextListener.mk(params)
      case _ => TlsListener.mk(params)
    }

  private[this] trait ListenerMaker {
    def mk(params: Stack.Params): Listener[Http2Frame, Http2Frame] = {
      def codec = H2FrameCodec.server(
        settings = Netty4H2Settings.mk(params),
        windowUpdateRatio = params[param.FlowControl.WindowUpdateRatio].ratio,
        autoRefillConnectionWindow = params[param.FlowControl.AutoRefillConnectionWindow].enabled
      )

      Netty4Listener(
        pipelineInit = pipelineInit(codec),
        params = params + Netty4Listener.BackPressure(false)
      )
    }

    protected[this] def pipelineInit(c: => H2FrameCodec): ChannelPipeline => Unit
  }

  private[this] object PlaintextListener extends ListenerMaker {
    override protected[this] def pipelineInit(codec: => H2FrameCodec) = { p: ChannelPipeline =>
      p.addLast(UnpoolHandler)
      p.addLast(new ServerUpgradeHandler(codec)); ()
    }
  }

  private[this] object TlsListener extends ListenerMaker {
    val PlaceholderKey = "h2 framer placeholder"
    override protected[this] def pipelineInit(codec: => H2FrameCodec) = { p: ChannelPipeline =>
      p.addLast(UnpoolHandler)
      p.addLast(PlaceholderKey, new ChannelDuplexHandler)
        .addLast("alpn", new Alpn(codec)); ()
    }

    private class Alpn(codec: => H2FrameCodec)
      extends ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_2) {

      override protected def configurePipeline(ctx: ChannelHandlerContext, proto: String): Unit =
        proto match {
          case ApplicationProtocolNames.HTTP_2 =>
            ctx.channel.config.setAutoRead(true)
            ctx.pipeline.replace(PlaceholderKey, "h2 framer", codec); ()

          // TODO case ApplicationProtocolNames.HTTP_1_1 =>
          case proto => throw new IllegalStateException(s"unknown protocol: $proto")
        }
    }
  }

}
