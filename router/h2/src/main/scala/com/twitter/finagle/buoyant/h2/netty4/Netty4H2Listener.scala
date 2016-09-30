package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.server.Listener
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.handler.codec.http.{HttpServerCodec, HttpServerUpgradeHandler}
import io.netty.handler.codec.http2._
import io.netty.util.AsciiString

object Netty4H2Listener {

  // private val log = com.twitter.logging.Logger.get(getClass.getName)

  val H2UpgraderKey = "h2 upgrader"
  val H2FramerKey = "h2 framer"

  def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] = {
    /*
     * XXX The stream is configured with Netty4ServerChannelinitializer,
     * which expects that the inbound side of the pipeline transmits
     * bytes. However, the HTTP/2 listener uses frames and
     * de-multiplexes the connection earlier in the pipeline so that
     * each child stream transmits Http2StreamFrame objects.
     *
     * ChannelStatsHandler logs a tremendous amount of errors when
     * it processes non-ByteBuf messages, and so for now we just
     * remove it from each stream pipeline.
     */

    def initHttp2Connection(stream: ChannelInitializer[Channel]) = new ChannelInitializer[Channel] {
      def initChannel(ch: Channel): Unit = {
        val _ = ch.pipeline.addLast(H2UpgraderKey, new H2Upgrader(prepChildStream(stream)))
      }
    }

    def prepChildStream(stream: ChannelInitializer[Channel]) = new ChannelInitializer[Channel] {
      def initChannel(ch: Channel): Unit = {
        // ch.pipeline.addLast(new Http2FrameStatsHandler(statsReceiver.scope("stream")))
        ch.pipeline.addLast(stream)
        val _ = ch.pipeline.addLast(new ChannelInitializer[Channel] {
          def initChannel(ch: Channel): Unit = {
            // XXX this was necessary but maybe isn't now..
            // val _ = ch.pipeline.remove("channel stats")
          }
        })
      }
    }

    // There's no need to configure anything on the stream channel,
    // but if we wanted to do install anything on each stream, this
    // would be where it happens.
    def initHttp2Stream(pipeline: ChannelPipeline): Unit = {}

    Netty4Listener(
      pipelineInit = initHttp2Stream,
      handlerDecorator = initHttp2Connection,
      // Netty4's Http2 Codec doesn't allow finagle-style
      // backpressure; we instead rely on Http2's flow control.
      params = params + Netty4Listener.BackPressure(false)
    )
  }
}
