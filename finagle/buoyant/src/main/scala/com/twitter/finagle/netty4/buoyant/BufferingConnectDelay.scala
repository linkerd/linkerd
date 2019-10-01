package com.twitter.finagle.netty4
package buoyant

import com.twitter.finagle.netty4.channel.{ConnectPromiseDelayListeners, BufferingChannelOutboundHandler}
import io.netty.channel._
import io.netty.handler.proxy.ProxyHandler
import java.net.SocketAddress

/**
 *
 *
 * Modified from com.twitter.finagle.netty...
 */
private[finagle] class BufferingConnectDelay
  extends ChannelDuplexHandler
  with BufferingChannelOutboundHandler { self =>

  import ConnectPromiseDelayListeners._

  /*
   * We manage two promises:
   * - `inp` informs the inward pipeline when the channel is
   *    connected and initialized properly.
   * - `outp` is satisfied when the outward pipeline is connected.
   *
   * Cancellations are propagated outward--failures, inward.
   *
   * Outbound writes are buffered until `outp` is satisfied. If the
   * outward channel was connected successfully, the inward promise
   * is satisfied and buffered outbound writes are written. If the
   * outward channel fails to connect, the inward promise fails and
   * the promise for each buffered request is satisfied with a
   * connection (requeueable) connection exception.
   */

  override def connect(
    ctx: ChannelHandlerContext,
    remote: SocketAddress,
    local: SocketAddress,
    inp: ChannelPromise
  ): Unit = {
    val outp = ctx.newPromise()
    inp.addListener(proxyCancellationsTo(outp, ctx))
    outp.addListener(proxyFailuresTo(inp))
    outp.addListener(proxyActiveTo(inp, ctx))

    val _ = ctx.connect(remote, local, outp)
  }

  private[this] def proxyActiveTo(inp: ChannelPromise, ctx: ChannelHandlerContext) =
    new ChannelFutureListener {
      override def operationComplete(future: ChannelFuture): Unit =
        if (future.isSuccess) {
          val _ = ctx.pipeline.addLast(handleActiveTo(inp))
        } else if (inp.tryFailure(future.cause)) {
          failPendingWrites(future.cause)
        }
    }

  private[this] def handleActiveTo(inp: ChannelPromise): ChannelHandler =
    new ChannelInboundHandlerAdapter { drainer =>
      override def channelActive(ctx: ChannelHandlerContext): Unit =
        if (inp.trySuccess()) {
          ctx.pipeline.remove(drainer)
          val _ = ctx.pipeline.remove(self) // drains pending writes when removed
        }

      override def exceptionCaught(ctx: ChannelHandlerContext, exn: Throwable): Unit =
        if (inp.tryFailure(exn)) failPendingWrites(exn)
    }
}
