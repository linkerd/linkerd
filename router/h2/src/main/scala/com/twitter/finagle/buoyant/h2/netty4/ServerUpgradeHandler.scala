package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.logging.Logger
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.handler.codec.http.{HttpServerCodec, HttpServerUpgradeHandler}
import io.netty.handler.codec.http2._
import io.netty.util.AsciiString

/*
 * XXX TODO -- this currently isn't used, but it should be.
 */

object ServerUpgradeHandler {
  private val log = Logger.get(getClass.getName)

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
class ServerUpgradeHandler(stream: ChannelHandler) extends ChannelDuplexHandler {
  import ServerUpgradeHandler._

  private[this] val rawH2 = new Http2FrameCodec(true /*server*/ )

  private[this] val h1 = new HttpServerCodec

  // private[this] val upgradedH2 = new HttpServerUpgradeHandler.UpgradeCodecFactory {
  //   // TODO we could transparently upgrade h1 requests as h2 requests, couldn't we?
  //   override def newUpgradeCodec(proto: CharSequence): HttpServerUpgradeHandler.UpgradeCodec =
  //     if (isH2C(proto)) new Http2ServerUpgradeCodec(rawH2) else null
  // }

  // private[this] val upgrader = new HttpServerUpgradeHandler(h1, upgradedH2)

  /**
   * Detect the HTTP2 connection preface to support Prior Knowledge
   * HTTP2 (i.e. gRPC). If that doesn't exist try to upgrade from HTTP/1.
   */
  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
    obj match {
      case bb: ByteBuf if isPreface(bb) =>
        // If the connection starts with the magical prior-knowledge
        // preface, just assume we're speaking plain h2c.
        ctx.pipeline.addLast("h2", rawH2)
        ctx.pipeline.addLast("h2 debug", new DebugHandler("s.frame"))
        ctx.pipeline.addLast("h2 stream", stream)

      // case bb: ByteBuf =>
      //   // Otherwise, Upgrade from h1 to h2
      //   ctx.pipeline.addLast("h1", h1)
      //   ctx.pipeline.addLast("h2 upgrader", upgrader)

      case _ => // Fall through and pass on the read.
    }

    // Stop trying to upgrade the protocol.
    ctx.pipeline.remove(this)
    log.debug(s"h2 server pipeline: installing framer: ${ctx.pipeline}")

    // Pass it on.
    ctx.fireChannelRead(obj); ()
  }
}
