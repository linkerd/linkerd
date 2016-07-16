package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.server.Listener
import io.netty.channel.{Channel, ChannelInitializer}
import io.netty.handler.codec.http2.{Http2Codec, Http2StreamFrame}
import scala.language.implicitConversions

/**
 * Please note that the listener cannot be used for TLS yet.
 */
object Http2Listener {

  def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] =
    Netty4Listener(
      pipelineInit = { _ => },
      handlerDecorator = { stream =>
      new ChannelInitializer[Channel] {
        def initChannel(ch: Channel): Unit = {
          val _ = ch.pipeline.addLast(new Http2Codec(true /*server*/ , stream))
        }
      }
    },
      // XXX Netty4's Http2 Codec doesn't support backpressure yet.
      // See https://github.com/netty/netty/issues/3667#issue-69640214
      params = params + Netty4Listener.BackPressure(false)
    )
}
