package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.buoyant.BufferingConnectDelay
import com.twitter.finagle.transport.Transport
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http2._
import java.net.SocketAddress

object Netty4H2Transporter {

  def mk(addr: SocketAddress, params0: Stack.Params): Transporter[Http2Frame, Http2Frame] = {
    val params = params0 +
      // We rely on HTTP/2 flow control rather than socket-level
      // backpressure.
      Netty4Transporter.Backpressure(false)

    // Each client connection pipeline is framed into HTTP/2 stream
    // frames. The connect promise does not fire (and therefore
    // transports are not created) until a connection is fully
    // initialized (and protocol initialization has completed). All
    // stream frame writes are buffered until this time.

    val settings = Netty4H2Settings.mk(params).pushEnabled(false)
    def framer = H2FrameCodec.client(
      settings = settings,
      windowUpdateRatio = params[param.FlowControl.WindowUpdateRatio].ratio,
      autoRefillConnectionWindow = params[param.FlowControl.AutoRefillConnectionWindow].enabled
    )

    val pipelineInit: ChannelPipeline => Unit =

      if (params[Transport.ClientSsl].e.isDefined) {
        // secure
        p =>
          {
            p.addLast(UnpoolHandler)
            p.addLast(FramerKey, framer); ()
          }
      } else {
        // insecure
        params[param.ClientPriorKnowledge] match {
          case param.ClientPriorKnowledge(false) =>
            // TODO support h1 upgrades
            throw new IllegalArgumentException("client prior knowledge must be enabled")

          case param.ClientPriorKnowledge(true) =>
            // Prior Knowledge: ensure messages are buffered until
            // handshake completes.
            p => {
              p.addLast(UnpoolHandler)
              p.addLast(framer)
              p.addLast(new BufferingConnectDelay); ()
            }
        }
      }

    Netty4Transporter.raw(pipelineInit, addr, params)
  }

  private val FramerKey = "h2 framer"
}
