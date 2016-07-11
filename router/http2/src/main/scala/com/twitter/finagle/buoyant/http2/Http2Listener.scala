package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.{Netty4Listener, Netty4Transporter}
import com.twitter.finagle.server.Listener
import com.twitter.logging.Logger
import io.netty.channel._
import io.netty.handler.codec.http2._
import io.netty.handler.logging.{LogLevel, LoggingHandler}
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
        ch.pipeline.addLast("wire debug", new LoggingHandler(LogLevel.INFO)).ignoreme
        ch.pipeline.addLast("framer", new Http2FrameCodec(true)).ignoreme
        ch.pipeline.addLast("debug.frame", new DebugHandler("srv.frame")).ignoreme
        ch.pipeline.addLast("muxer", new Http2MultiplexCodec(true, null, stream)).ignoreme
      }
    }

  private[this] val prepareStream: ChannelPipeline => Unit =
    _.addLast(new DebugHandler("srv.stream")).ignoreme

  def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] =
    Netty4Listener(
      pipelineInit = prepareStream,
      // XXX Netty4's Http2 Codec doesn't support backpressure yet.
      // See https://github.com/netty/netty/issues/3667#issue-69640214
      params = params + Netty4Listener.BackPressure(false),
      handlerDecorator = mkHttp2
    )
}
