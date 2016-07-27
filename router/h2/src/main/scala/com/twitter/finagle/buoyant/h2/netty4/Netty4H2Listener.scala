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

/**
 * Please note that the listener cannot be used for TLS yet.
 */
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
            val _ = ch.pipeline.remove("channel stats")
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
      // XXX Netty4's Http2 Codec doesn't support backpressure yet.
      // See https://github.com/netty/netty/issues/3667#issue-69640214
      params = params + Netty4Listener.BackPressure(false)
    )
  }

  /**
   * Accept either H2C requests (beginning with a Connection preface)
   * or HTTP requests with h2c protocol upgrading.
   */
  private class H2Upgrader(stream: ChannelHandler) extends ChannelDuplexHandler {
    private[this] val prefaceBuf = Http2CodecUtil.connectionPrefaceBuf
    private[this] val prefaceLen = prefaceBuf.readableBytes

    private[this] def isPreface(bb: ByteBuf) =
      (bb.readableBytes >= prefaceLen &&
        bb.slice(0, prefaceLen).equals(prefaceBuf))

    private[this] val rawH2 = new Http2Codec(true /*server*/ , stream)

    private[this] val h1 = new HttpServerCodec
    private[this] val upgradedH2 = new HttpServerUpgradeHandler.UpgradeCodecFactory {
      override def newUpgradeCodec(proto: CharSequence): HttpServerUpgradeHandler.UpgradeCodec =
        if (isH2C(proto)) new Http2ServerUpgradeCodec(rawH2) else null
    }

    /**
     * Detect the HTTP2 connection preface to support Prior Knowledge
     * HTTP2 (i.e. gRPC). If that doesn't exist
     */
    override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
      obj match {
        case bb: ByteBuf if isPreface(bb) =>
          // TODO buffer at least preface.readableBytes from bb --
          // there's got to be a utility handler for this, right?
          ctx.pipeline.addLast("h2", rawH2)

        case bb: ByteBuf => // Try to upgrade from HTTP1
          ctx.pipeline.addLast("h1", h1)
          ctx.pipeline.addLast("h1 to h2", new HttpServerUpgradeHandler(h1, upgradedH2))

        case _ => // iunno, just pass it on.
      }

      // Stop trying to upgrade the protocol.
      ctx.pipeline.remove(this)

      // Pass it on.
      val _ = ctx.fireChannelRead(obj)
    }
  }

  private def isH2C(proto: CharSequence): Boolean =
    AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, proto)
}
