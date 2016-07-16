package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.buoyant.BufferingConnectDelay
import com.twitter.finagle.transport.TransportProxy
import com.twitter.io.Charsets
import io.netty.channel._
import io.netty.handler.codec.http2._
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean

object Http2Transporter {

  def mk(params0: Stack.Params): Transporter[Http2StreamFrame, Http2StreamFrame] = {
    val initializer: ChannelPipeline => Unit = { cp =>
      val _ = cp.addLast(
        new Http2FrameCodec(false /*server*/ ),
        new BufferingConnectDelay
      )
    }

    // Netty4's Http2 Codec doesn't support backpressure yet.
    // See https://github.com/netty/netty/issues/3667#issue-69640214
    val params = params0 + Netty4Transporter.Backpressure(false)
    Netty4Transporter(initializer, params)
  }
}
