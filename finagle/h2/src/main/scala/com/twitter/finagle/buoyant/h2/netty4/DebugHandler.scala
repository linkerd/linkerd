package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.logging.Logger
import io.netty.channel._
import io.netty.handler.codec.http2._

private[h2] class DebugHandler(prefix: String)
  extends ChannelDuplexHandler {

  private[this] val log = Logger.get("h2")

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    log.debug("%s.handlerAdded %s", prefix, ctx.channel)
    super.handlerAdded(ctx)
  }

  override def handlerRemoved(ctx: ChannelHandlerContext): Unit = {
    log.debug("%s.handlerRemoved %s", prefix, ctx.channel)
    super.handlerRemoved(ctx)
  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    log.debug("%s.channelActive %s", prefix, ctx.channel)
    super.channelActive(ctx)
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    log.debug("%s.channelInactive %s", prefix, ctx.channel)
    super.channelInactive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
    log.debug("%s.channelRead %s %s", prefix, ctx.channel, obj)
    super.channelRead(ctx, obj)
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    log.debug("%s.channelReadComplete %s", prefix, ctx.channel)
    super.channelReadComplete(ctx)
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, ev: Any): Unit = {
    log.debug("%s.userEventTriggered %s %s", prefix, ctx.channel, ev)
    val _ = ctx.fireUserEventTriggered(ev)
  }

  private[this] val counter = new java.util.concurrent.atomic.AtomicLong
  override def write(ctx: ChannelHandlerContext, obj: Any, p: ChannelPromise): Unit = {
    val reqid = counter.incrementAndGet()

    val objstr = obj match {
      case f: Http2HeadersFrame =>
        val eos = if (f.isEndStream) "eos" else "---"
        s"${f.stream.id} $eos HEADERS"

      case f: Http2DataFrame =>
        val eos = if (f.isEndStream) "eos" else "---"
        s"${f.stream.id} $eos DATA ${f.content.capacity}"

      case f: Http2ResetFrame =>
        s"${f.stream.id} eos ${f.name}"

      case f: Http2StreamFrame =>
        s"${f.stream.id} --- ${f.name}"

      case obj => obj.toString
    }

    log.debug("%d %s.write %s [%s]", reqid, prefix, ctx.channel, objstr)
    p.addListener(new ChannelFutureListener {
      override def operationComplete(cf: ChannelFuture): Unit = {
        cf.cause match {
          case null => log.debug("%d %s.write.complete %s [%s] %s", reqid, prefix, cf.channel, objstr, cf)
          case e => log.debug(e, "%d %s.write.complete %s [%s] %s", reqid, prefix, cf.channel, objstr, cf)
        }
      }
    })

    super.write(ctx, obj, p)
  }

  override def close(ctx: ChannelHandlerContext, p: ChannelPromise): Unit = {
    log.debug("%s.close %s", prefix, ctx.channel)
    p.addListener(new ChannelFutureListener {
      override def operationComplete(cf: ChannelFuture): Unit =
        log.debug("%s.close.complete %s %s", prefix, ctx.channel, cf)
    })
    super.close(ctx, p)
  }
}
