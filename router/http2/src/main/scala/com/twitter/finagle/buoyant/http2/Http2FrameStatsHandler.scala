package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.{Duration, Memoize, Stopwatch}
import io.netty.channel._
import io.netty.handler.codec.http2._

object Http2FrameStatsHandler {

  private class KindStats(stats: StatsReceiver) {
    val count = stats.counter("count")
    val durations = stats.stat("us")

    def measure(f: Http2StreamFrame, d: Duration): Unit = {
      count.incr()
      durations.add(d.inMicroseconds)
    }
  }

  private class DataStats(stats: StatsReceiver) extends KindStats(stats) {
    val bytes = stats.stat("bytes")

    override def measure(f: Http2StreamFrame, d: Duration): Unit = {
      f match {
        case f: Http2DataFrame =>
          bytes.add(f.content.readableBytes + f.padding)
        case _ =>
      }
      super.measure(f, d)
    }
  }

  private case class FrameStats(stats: StatsReceiver) {
    val kinds = Memoize[String, KindStats] {
      case "DATA" => new DataStats(stats.scope("DATA"))
      case k => new KindStats(stats.scope(k))
    }

    def apply(obj: Any, d: Duration): Unit = obj match {
      case f: Http2StreamFrame => kinds(f.name).measure(f, d)
      case _ =>
    }
  }

}

class Http2FrameStatsHandler(stats: StatsReceiver) extends ChannelDuplexHandler {

  import Http2FrameStatsHandler._

  private[this] val recvStats = new FrameStats(stats.scope("recv"))
  private[this] val sendStats = new FrameStats(stats.scope("send"))

  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
    val t = Stopwatch.start()
    ctx.fireChannelRead(obj)
    recvStats(obj, t())
  }

  override def write(ctx: ChannelHandlerContext, obj: Any, p: ChannelPromise): Unit = {
    val t = Stopwatch.start()
    ctx.write(obj, p)
    val _ = p.addListener(new ChannelFutureListener {
      def operationComplete(cf: ChannelFuture): Unit = sendStats(obj, t())
    })
  }
}
