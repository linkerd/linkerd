package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.{Duration, Memoize, Stopwatch}
import io.netty.channel._
import io.netty.handler.codec.http2._

object Http2FrameStatsHandler {

  private class KindStats(stats: StatsReceiver) {
    val count = stats.counter("count")
    val durations = stats.stat("us")
  }

  private class DataStats(stats: StatsReceiver) extends KindStats(stats) {
    val bytes = stats.stat("bytes")
  }

  private case class FrameStats(statsReceiver: StatsReceiver) {
    val dataStats = new DataStats(statsReceiver.scope("DATA"))
    val kinds = Memoize[String, KindStats] {
      case "DATA" => dataStats
      case k => new KindStats(statsReceiver.scope(k))
    }

    def apply(obj: Any)(op: (() => Unit) => Unit): Unit = obj match {
      case f: Http2DataFrame =>
        val stats = dataStats
        val bytes = f.content.readableBytes + f.padding
        val snap = Stopwatch.start()
        val record: () => Unit = { () =>
          stats.durations.add(snap().inMicroseconds)
          stats.count.incr()
          stats.bytes.add(bytes)
        }
        op(record)

      case f: Http2StreamFrame =>
        val stats = kinds(f.name)
        val snap = Stopwatch.start()
        val record: () => Unit = { () =>
          stats.durations.add(snap().inMicroseconds)
          stats.count.incr()
        }
        op(record)

      case _ => op(() => ())
    }
  }

}

class Http2FrameStatsHandler(stats: StatsReceiver) extends ChannelDuplexHandler {

  import Http2FrameStatsHandler._

  private[this] val recvStats = new FrameStats(stats.scope("recv"))
  private[this] val sendStats = new FrameStats(stats.scope("send"))

  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit =
    recvStats(obj) { done =>
      ctx.fireChannelRead(obj)
      done()
    }

  override def write(ctx: ChannelHandlerContext, obj: Any, p: ChannelPromise): Unit =
    sendStats(obj) { done =>
      ctx.write(obj, p)
      val _ = p.addListener(new ChannelFutureListener {
        def operationComplete(cf: ChannelFuture): Unit = done()
      })
    }
}
