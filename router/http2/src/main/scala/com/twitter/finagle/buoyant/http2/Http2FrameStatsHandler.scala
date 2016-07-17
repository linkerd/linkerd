package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.stats.StatsReceiver
import io.netty.channel._
import io.netty.handler.codec.http2._

class Http2FrameStatsHandler(stats: StatsReceiver) extends ChannelDuplexHandler {

  private[this] val recvStats = stats.scope("recv")
  private[this] val recvFrameCount = recvStats.counter("all")
  private[this] val recvUnknownCount = recvStats.counter("unknown")
  private[this] val recvHeaderSz = recvStats.stat("headers")
  private[this] val recvDataSz = recvStats.stat("data")
  private[this] val recvWindowSz = recvStats.stat("window")

  private[this] val sendStats = stats.scope("send")
  private[this] val sendFrameCount = sendStats.counter("all")
  private[this] val sendUnknownCount = sendStats.counter("unknown")
  private[this] val sendHeaderSz = sendStats.stat("headers")
  private[this] val sendDataSz = sendStats.stat("data")
  private[this] val sendWindowSz = sendStats.stat("window")

  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
    recvFrameCount.incr()
    obj match {
      case h: Http2HeadersFrame => recvHeaderSz.add(h.headers.size)
      case d: Http2DataFrame => recvDataSz.add(d.content.readableBytes + d.padding)
      case w: Http2WindowUpdateFrame => recvWindowSz.add(w.windowSizeIncrement)
      case _ =>
    }
    val _ = ctx.fireChannelRead(obj)
  }

  override def write(ctx: ChannelHandlerContext, obj: Any, p: ChannelPromise): Unit = {
    obj match {
      case h: Http2HeadersFrame => sendHeaderSz.add(h.headers.size)
      case d: Http2DataFrame => sendDataSz.add(d.content.readableBytes + d.padding)
      case w: Http2WindowUpdateFrame => sendWindowSz.add(w.windowSizeIncrement)
      case _ =>
    }
    val _ = ctx.write(obj, p)
  }
}
