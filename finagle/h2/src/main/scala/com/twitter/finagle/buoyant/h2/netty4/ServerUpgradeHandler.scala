package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.logging.Logger
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.handler.codec.http.{HttpServerCodec, HttpServerUpgradeHandler}
import io.netty.handler.codec.http2._
import io.netty.util.AsciiString

object ServerUpgradeHandler {
  private val log = Logger.get("h2")

  private val PrefaceBuf = Http2CodecUtil.connectionPrefaceBuf
  private val PrefaceLen = PrefaceBuf.readableBytes
  private def isPreface(bb: ByteBuf) =
    (bb.readableBytes >= PrefaceLen &&
      bb.slice(0, PrefaceLen).equals(PrefaceBuf))

  private def isH2C(proto: CharSequence): Boolean =
    AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, proto)
}

/**
 * Accept either H2C requests (beginning with a Connection preface)
 * or HTTP requests with h2c protocol upgrading.
 */
class ServerUpgradeHandler(h2Framer: H2FrameCodec) extends ChannelDuplexHandler {
  import ServerUpgradeHandler._

  // Parses HTTP/1 objects.
  private[this] val h1Codec = new HttpServerCodec

  // Intercepts HTTP/1 requests with the HTTP2-Settings headers and
  // initiate protocol upgrade.
  private[this] val upgrader =
    new HttpServerUpgradeHandler(h1Codec, new HttpServerUpgradeHandler.UpgradeCodecFactory {
      override def newUpgradeCodec(proto: CharSequence): HttpServerUpgradeHandler.UpgradeCodec =
        if (isH2C(proto)) new Http2FrameCodecServerUpgrader(h2Framer)
        else null
    })

  /**
   * Detect the HTTP2 connection preface to support Prior Knowledge
   * HTTP2 (i.e. gRPC). If that doesn't exist try to upgrade from HTTP/1.
   */
  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
    obj match {
      case bb: ByteBuf if isPreface(bb) =>
        // If the connection starts with the magical prior-knowledge
        // preface, just assume we're speaking plain h2c.
        ctx.pipeline.addAfter(ctx.name, "h2 framer", h2Framer)

      case bb: ByteBuf =>
        // Otherwise, Upgrade from h1 to h2
        ctx.pipeline.addAfter(ctx.name, "h1 codec", h1Codec)
        ctx.pipeline.addAfter("h1 codec", "h1 upgrade h2", upgrader)
      // TODO silently translate native h1 to h2

      case _ => // Fall through and pass on the read.
    }

    // Stop trying to upgrade the protocol.
    ctx.pipeline.remove(this)
    log.debug("h2 server pipeline: installing framer: %s", ctx.pipeline)

    // Pass it on.
    ctx.fireChannelRead(obj); ()
  }
}
