package io.netty.handler.codec.http2

import io.netty.channel.ChannelHandler

/**
 * Adapt Http2ServerUpgradeCodec to be instantiated with an Http2FrameCodec
 */
class Http2FrameCodecServerUpgrader(name: String, framer: H2FrameCodec, handler: ChannelHandler)
  extends Http2ServerUpgradeCodec(framer.connectionHandler) {
  def this(framer: H2FrameCodec) = this(null, framer, framer.connectionHandler)
}
