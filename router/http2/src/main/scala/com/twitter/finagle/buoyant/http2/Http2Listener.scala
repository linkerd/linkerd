package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.{Netty4Listener, Netty4Transporter}
import com.twitter.finagle.server.Listener
import com.twitter.logging.Logger
import io.netty.channel._
import io.netty.handler.codec.http2._
import io.netty.channel.socket.SocketChannel
import scala.language.implicitConversions

/**
 * Please note that the listener cannot be used for TLS yet.
 */
object Http2Listener {
  private[this] val log = Logger.get(getClass.getName)

  private trait ToIgnoreMe { def ignoreme: Unit }
  private object ToIgnoreMe extends ToIgnoreMe { val ignoreme = () }
  private implicit def toIgnoreMe(a: Any): ToIgnoreMe = ToIgnoreMe

  private[this] val mkHttp2: ChannelInitializer[Channel] => ChannelHandler =
    stream => new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel): Unit = {
        // ch.pipeline.addLast("debug.raw", new DebugHandler("srv.raw")).ignoreme

        ch.pipeline.addLast("framer", new Http2FrameCodec(true)).ignoreme
        // ch.pipeline.addLast("debug.frame", new DebugHandler("srv.frame")).ignoreme

        // If we want to intercept things like Settings messages, this
        // might be a good place to do that.  Eventually, we'll want
        // to surface window settings on streams across clients/servers.

        ch.pipeline.addLast("muxer", new Http2MultiplexCodec(true, null, stream)).ignoreme
        // ch.pipeline.addLast("debug.mux", new DebugHandler("srv.mux")).ignoreme

        log.info(s"srv: ${ch} ${ch.pipeline}")
      }
    }

  private[this] val prepareStream: ChannelPipeline => Unit = { _ => }

  def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] =
    Netty4Listener(
      pipelineInit = prepareStream,
      // XXX Netty4's Http2 Codec doesn't support backpressure yet.
      // See https://github.com/netty/netty/issues/3667#issue-69640214
      params = params + Netty4Listener.BackPressure(false),
      handlerDecorator = mkHttp2
    )
}
