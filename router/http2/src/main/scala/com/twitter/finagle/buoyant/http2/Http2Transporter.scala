package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.channel.BufferingChannelOutboundHandler
import com.twitter.finagle.transport.TransportProxy
import io.netty.channel._
import io.netty.handler.codec.http2._

private[http2] object Http2Transporter {

  def mk(params0: Stack.Params): Transporter[Http2StreamFrame, Http2StreamFrame] = {
    val initializer = { pipeline: ChannelPipeline =>
      // XXX this compile setting is sort of in the way, isn't it...
      val _0 = pipeline.addLast("h2.debug", new DebugHandler("client[raw]"))
      val _1 = pipeline.addLast("h2", new Http2FrameCodec(false /*server*/ ))
      val _2 = pipeline.addLast("h1.debug", new DebugHandler("client[h2]"))
    }

    // Netty4's Http2 Codec doesn't support backpressure yet.
    // See https://github.com/netty/netty/issues/3667#issue-69640214
    val params = params0 + Netty4Transporter.Backpressure(false)

    Netty4Transporter(initializer, params)
  }

}
