package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.Stopwatch
import io.netty.channel._

class TimingHandler(stats: StatsReceiver) extends ChannelDuplexHandler {

  private[this] val readTimes = stats.stat("read_us")

  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
    val snap = Stopwatch.start()
    ctx.fireChannelRead(obj)
    readTimes.add(snap().inMicroseconds)
  }

  private[this] val writeStats = stats.scope("write")
  private[this] val writeExecTimes = writeStats.stat("exec_us")
  private[this] val writeAckTimes = writeStats.stat("ack_us")

  override def write(ctx: ChannelHandlerContext, obj: Any, p: ChannelPromise): Unit = {
    val execSnap = Stopwatch.start()
    ctx.write(obj, p)
    writeExecTimes.add(execSnap().inMicroseconds)

    val ackSnap = Stopwatch.start()
    val _ = p.addListener(new ChannelFutureListener {
      override def operationComplete(cf: ChannelFuture): Unit = {
        writeAckTimes.add(ackSnap().inMicroseconds)
      }
    })
  }
}
