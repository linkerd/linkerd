package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.{Stack, param}
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.buoyant.BufferingConnectDelay
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.transport.TransportProxy
import com.twitter.io.Charsets
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http2.{Http2FrameCodec, Http2StreamFrame}
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean

object Http2Transporter {

  def mk(params0: Stack.Params): Transporter[Http2StreamFrame, Http2StreamFrame] = {
    val param.Stats(stats) = params0[param.Stats]
    val connStats = stats.scope("conn")

    // Each client connection pipeline is framed into HTTP/2 stream
    // frames. The connect promise does not fire (and therefore
    // transports are not created) until a connection is fully
    // initialized (and protocol initialization has completed). All
    // stream frame writes are buffered until this time.
    val initializer: ChannelPipeline => Unit = { cp =>
      val _ = cp.addLast(
        new TimingHandler(connStats.scope("outer")),

        new Http2FrameCodec(false /*server*/ ),
        new Http2FrameStatsHandler(stats.scope("frames")),
        new TimingHandler(connStats.scope("mid")),

        new BufferingConnectDelay,

        new TimingHandler(connStats.scope("inner"))
      )
    }

    // Netty4's Http2 Codec doesn't support backpressure yet.
    // See https://github.com/netty/netty/issues/3667#issue-69640214
    val params = params0 + Netty4Transporter.Backpressure(false)
    Netty4Transporter(initializer, params)
  }
}
