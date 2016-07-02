package com.twitter.finagle.buoyant.http2

import com.twitter.logging.Logger
import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise}

private[http2] class DebugHandler(prefix: String)
  extends ChannelDuplexHandler {

  private[this] val log = Logger.get(getClass.getName)

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.handlerAdded $ctx")
    super.handlerAdded(ctx)
  }

  override def handlerRemoved(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.handlerRemoved $ctx")
    super.handlerRemoved(ctx)
  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.channelActive $ctx")
    super.channelActive(ctx)
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.channelInactive $ctx")
    super.channelInactive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
    log.info(s"$prefix.channelRead $ctx $obj")
    super.channelRead(ctx, obj)
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.channelReadComplete $ctx")
    super.channelReadComplete(ctx)
  }

  override def write(ctx: ChannelHandlerContext, obj: Any, p: ChannelPromise): Unit = {
    log.info(s"$prefix.write $ctx $obj")
    super.write(ctx, obj, p)
  }

  override def close(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = {
    log.info(s"$prefix.close $ctx")
    super.close(ctx, promise)
  }
}
