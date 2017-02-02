package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.TlsClientPrep.TransportSecurity
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.buoyant.{BufferingConnectDelay, Netty4ClientTls}
import com.twitter.finagle.netty4.channel.DirectToHeapInboundHandler
import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPipeline}
import io.netty.handler.codec.http2._
import io.netty.handler.ssl.ApplicationProtocolNames

object Netty4H2Transporter {

  def mk(params0: Stack.Params): Transporter[Http2Frame, Http2Frame] = {
    val params = params0 +
      // We rely on HTTP/2 flow control rather than socket-level
      // backpressure.
      Netty4Transporter.Backpressure(false) +
      Netty4ClientTls.ApplicationProtocols(ApplicationProtocolNames.HTTP_2)

    // Each client connection pipeline is framed into HTTP/2 stream
    // frames. The connect promise does not fire (and therefore
    // transports are not created) until a connection is fully
    // initialized (and protocol initialization has completed). All
    // stream frame writes are buffered until this time.

    // TODO configure settings from params
    def framer = H2FrameCodec.client()

    val pipelineInit: ChannelPipeline => Unit =
      params[TransportSecurity].config match {
        case TransportSecurity.Insecure =>
          params[param.ClientPriorKnowledge] match {
            case param.ClientPriorKnowledge(false) =>
              // TODO support h1 upgrades
              throw new IllegalArgumentException("client prior knowledge must be enabled")

            case param.ClientPriorKnowledge(true) =>
              // Prior Knowledge: ensure messages are buffered until
              // handshake completes.
              p => {
                p.addLast(DirectToHeapInboundHandler)
                p.addLast(framer)
                p.addLast(new BufferingConnectDelay); ()
              }
          }

        case TransportSecurity.Secure() =>
          // Netty4Transporter has already installed `ssl` and
          // `sslConnect` channel handlers. The Netty4ClientTls handler
          // replaces these handlers with the `tls` and `tlsConnect`
          // handlers, which are configured to advertise h2 support.
          p => {
            p.addLast(Netty4ClientTls.handler(params))
            p.addLast(DirectToHeapInboundHandler)
            p.addLast(FramerKey, framer); ()
          }
      }

    Netty4Transporter(pipelineInit, params)
  }

  private val FramerKey = "h2 framer"
}
