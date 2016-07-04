package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.{Netty4Listener, Netty4Transporter}
import com.twitter.finagle.server.Listener
import com.twitter.logging.Logger
import io.netty.channel._
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.codec.http2._
import io.netty.channel.socket.SocketChannel
import scala.language.implicitConversions

/**
 * Please note that the listener cannot be used for TLS yet.
 */
object Http2Listener {
  private[this] val log = Logger.get(getClass.getName)

  private trait ToUnit { def unit: Unit }
  private object ToUnit extends ToUnit { val unit = () }
  private implicit def toUnit(a: Any): ToUnit = ToUnit

  private[this] val mkHttp2: ChannelInitializer[Channel] => ChannelHandler =
    stream => new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline.addLast("debug.raw", new DebugHandler("srv.raw")).unit

        ch.pipeline.addLast("framer", new Http2FrameCodec(true)).unit
        ch.pipeline.addLast("debug.frame", new DebugHandler("srv.frame")).unit

        // If we want to intercept things like Settings messages, this
        // might be a good place to do that.  Eventually, we'll want
        // to surface window settings on streams across clients/servers.

        ch.pipeline.addLast("muxer", new Http2MultiplexCodec(true, null, stream)).unit
        ch.pipeline.addLast("debug.mux", new DebugHandler("srv.mux")).unit

        log.info(s"srv: ${ch} ${ch.pipeline}")
      }
    }

  private[this] val pipelineInit: ChannelPipeline => Unit = { pipeline =>
    pipeline.addLast("debug.stream", new DebugHandler("srv.stream"))
    log.info(s"srv.stream.pipeline: $pipeline")
  }

  def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2Frame] =
    Netty4Listener(
      pipelineInit = pipelineInit,
      // XXX Netty4's Http2 Codec doesn't support backpressure yet.
      // See https://github.com/netty/netty/issues/3667#issue-69640214
      params = params + Netty4Listener.BackPressure(false),
      handlerDecorator = mkHttp2
    )
}
