package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.{Netty4Listener, Netty4Transporter}
import com.twitter.finagle.server.Listener
import com.twitter.logging.Logger
import io.netty.channel._
import io.netty.handler.codec.http2.{Http2Frame, Http2FrameCodec, Http2MultiplexCodec}
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

  private[this] val isServer = true

  def mk(params0: Stack.Params = Stack.Params.empty): Listener[Http2Frame, Http2Frame] = {
    val mkInitializer: (ChannelInitializer[Channel] => ChannelHandler) =
      streamHandler => new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel): Unit = {
          val framer = new Http2FrameCodec(isServer)
          val mux = new Http2MultiplexCodec(isServer, null, streamHandler)

          ch.pipeline.addLast("debug.socket", new DebugHandler("srv.socket")).unit
          ch.pipeline.addLast("framer", framer).unit
          ch.pipeline.addLast("debug.framer", new DebugHandler("srv.framer")).unit
          ch.pipeline.addLast("mux", mux).unit
          ch.pipeline.addLast("debug.mux", new DebugHandler("srv.mux")).unit

          log.info(s"srv pipeline: ${ch} ${ch.pipeline}")
        }
      }

    val prepareStream: (ChannelPipeline => Unit) =
      pipeline => {
        pipeline.addLast("debug.srv.stream", new DebugHandler("srv.stream")).unit
        log.info(s"srv.stream pipeline: $pipeline")
      }

    // Netty4's Http2 Codec doesn't support backpressure yet.
    // See https://github.com/netty/netty/issues/3667#issue-69640214
    val params = params0 + Netty4Transporter.Backpressure(false)

    Netty4Listener[Http2Frame, Http2Frame](
      pipelineInit = prepareStream,
      handlerDecorator = mkInitializer,
      params = params
    )
  }
}
