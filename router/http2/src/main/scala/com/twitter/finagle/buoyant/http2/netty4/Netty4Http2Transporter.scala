package com.twitter.finagle.buoyant.http2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.buoyant.BufferingConnectDelay
import com.twitter.finagle.transport.TransportProxy
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http2.{Http2FrameCodec, Http2StreamFrame}

object Netty4Http2Transporter {

  def mk(params0: Stack.Params): Transporter[Http2StreamFrame, Http2StreamFrame] = {
    // Each client connection pipeline is framed into HTTP/2 stream
    // frames. The connect promise does not fire (and therefore
    // transports are not created) until a connection is fully
    // initialized (and protocol initialization has completed). All
    // stream frame writes are buffered until this time.
    val initializer: ChannelPipeline => Unit = { cp =>
      cp.addLast(new Http2FrameCodec(false /*server*/ ))
      val _ = cp.addLast(new BufferingConnectDelay)
    }

    // Netty4's Http2 Codec doesn't support backpressure yet.
    // See https://github.com/netty/netty/issues/3667#issue-69640214
    val params = params0 + Netty4Transporter.Backpressure(false)
    Netty4Transporter(initializer, params)

  }
}
