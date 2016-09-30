package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.server.Listener
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.handler.codec.http2.{Http2Codec, Http2StreamFrame}

/**
 * Based on com.twitter.finagle.http2.Http2Listener
 */
object Netty4H2Listener {

  def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] = {
    PriorKnowledgeListener.mk(params)
  }

  private[this] object PriorKnowledgeListener {

    def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] =
      Netty4Listener(
        pipelineInit = pipelineInit,
        params = params + Netty4Listener.BackPressure(false),
        setupMarshalling = setupMarshalling
      )

    val PlaceholderKey = "prior knowledge placeholder"
    private[this] object PlaceholderHandler extends ChannelDuplexHandler

    // we inject a dummy handler so we can replace it with the real stuff
    // after we get `init` in the setupMarshalling phase.
    private[this] val pipelineInit: ChannelPipeline => Unit = { pipeline =>
      val _ = pipeline.addLast(PlaceholderKey, PlaceholderHandler)
    }

    private[this] val setupMarshalling: ChannelInitializer[Channel] => ChannelHandler = { init =>
      val initializer = new ChannelInitializer[Channel] {
        def initChannel(ch: Channel): Unit = {
          val _ = ch.pipeline.addLast(init)
        }
      }
      new ChannelInitializer[Channel] {
        def initChannel(ch: Channel): Unit = {
          val _ = ch.pipeline.replace(PlaceholderKey, "h2Codec", new Http2Codec(true, initializer))
        }
      }
    }
  }

}
