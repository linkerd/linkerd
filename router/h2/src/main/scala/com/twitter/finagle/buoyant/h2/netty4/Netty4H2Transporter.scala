package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.buoyant.BufferingConnectDelay
import com.twitter.finagle.transport.TransportProxy
import io.buoyant.router.H2
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http2.{Http2FrameCodec, Http2StreamFrame}

object Netty4H2Transporter {

  def mk(params0: Stack.Params): Transporter[Http2StreamFrame, Http2StreamFrame] = {
    // We rely on flow control rather than socket-level backpressure.
    val params = params0 + Netty4Transporter.Backpressure(false)

    // Each client connection pipeline is framed into HTTP/2 stream
    // frames. The connect promise does not fire (and therefore
    // transports are not created) until a connection is fully
    // initialized (and protocol initialization has completed). All
    // stream frame writes are buffered until this time.
    val initializer: ChannelPipeline => Unit = { p =>
      p.addLast(new DebugHandler("c.bytes"))
      p.addLast(new Http2FrameCodec(false /*server*/ ))
      p.addLast(new BufferingConnectDelay)
      val _ = p.addLast(new DebugHandler("c.frame"))
    }
    Netty4Transporter(initializer, params)
  }
}
