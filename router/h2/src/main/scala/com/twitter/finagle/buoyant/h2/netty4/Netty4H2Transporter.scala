package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.buoyant.BufferingConnectDelay
import com.twitter.finagle.transport.{TlsConfig, Transport}
import io.buoyant.router.H2
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http2.{Http2FrameCodec, Http2StreamFrame}

object Netty4H2Transporter {

  def mk(params0: Stack.Params): Transporter[Http2StreamFrame, Http2StreamFrame] = {
    // We rely on flow control rather than socket-level backpressure.
    val params = params0 + Netty4Transporter.Backpressure(false)
    val param.ClientPriorKnowledge(pk) = params[param.ClientPriorKnowledge]
    val Transport.Tls(tlsConfig) = params[Transport.Tls]

    // Each client connection pipeline is framed into HTTP/2 stream
    // frames. The connect promise does not fire (and therefore
    // transports are not created) until a connection is fully
    // initialized (and protocol initialization has completed). All
    // stream frame writes are buffered until this time.
    def framer = new Http2FrameCodec(false /*server*/ )
    val initializer: ChannelPipeline => Unit = tlsConfig match {
      case TlsConfig.Disabled if !pk =>
        // TODO support h1 upgrades
        throw new IllegalArgumentException("client prior knowledge must be enabled")

      case TlsConfig.Disabled =>
        // Prior Knowledge: ensure messages are buffered until handshake completes.
        p => { p.addLast(framer).addLast(new BufferingConnectDelay); () }

      case _ =>
        // TLS is configured by the transport, so just install a framer.
        p => { p.addLast(framer); () }
    }

    Netty4Transporter(initializer, params)
  }
}
