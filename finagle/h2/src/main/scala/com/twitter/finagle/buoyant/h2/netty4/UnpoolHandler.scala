package com.twitter.finagle.buoyant.h2
package netty4

import io.netty.buffer.{ByteBuf, ByteBufHolder, EmptyByteBuf, Unpooled}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}

/**
 * This was originally copied from finagle's implementation of AnyToHeapInboundHandler:
 * https://github.com/twitter/finagle/blob/c86789cf0e064483ebf4509b52c9a216c31dd134/finagle-netty4/src/main/scala/com/twitter/finagle/netty4/channel/AnyToHeapInboundHandler.scala
 *
 * An inbound channel handler that copies byte buffers onto the JVM heap
 * and gives them a deterministic lifecycle. This handler also makes sure to
 * use the unpooled byte buffer (regardless of what channel's allocator is) as
 * its destination thereby defining a clear boundaries between pooled and unpooled
 * environments.
 *
 * @note If the input buffer is not readable it's still guaranteed to be released
 *       and replaced with EmptyByteBuf.
 *
 * @note This handler recognizes both ByteBuf's and ByteBufHolder's (think of HTTP
 *       messages extending ByteBufHolder's in Netty).
 *
 * @note If your protocol manages ref-counting or if you are delegating ref-counting
 *       to application space you don't need this handler in your pipeline. Every
 *       other use case needs this handler or you will with very high probability
 *       incur a direct buffer leak.
 */
@Sharable
object UnpoolHandler extends ChannelInboundHandlerAdapter {
  private[this] final def copyOnHeapAndRelease(bb: ByteBuf): ByteBuf = {
    try {
      if (bb.readableBytes > 0) Unpooled.buffer(bb.readableBytes, bb.capacity).writeBytes(bb)
      else Unpooled.EMPTY_BUFFER
    } finally {
      val _ = bb.release()
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = msg match {
    case bb: ByteBuf =>
      val _ = ctx.fireChannelRead(copyOnHeapAndRelease(bb))

    // This case is special since it helps to avoid unnecessary `replace`
    // when the underlying content is already `EmptyByteBuffer`.
    case bbh: ByteBufHolder if bbh.content.isInstanceOf[EmptyByteBuf] =>
      val _ = ctx.fireChannelRead(bbh)

    case bbh: ByteBufHolder =>
      val onHeapContent = copyOnHeapAndRelease(bbh.content)
      val _ = ctx.fireChannelRead(bbh.replace(onHeapContent))

    case _ =>
      val _ = ctx.fireChannelRead(msg)
  }
}
