package com.twitter.finagle.netty4
package buoyant

import com.twitter.finagle.netty4.channel.{ConnectPromiseDelayListeners, BufferingChannelOutboundHandler}
import io.netty.channel._
import io.netty.handler.proxy.ProxyHandler
import java.net.SocketAddress

// Modified from com.twitter.finagle.netty...
private[finagle] class Http2PriorKnowledgeInitHandler
  extends ChannelDuplexHandler
  with BufferingChannelOutboundHandler
  with ConnectPromiseDelayListeners { buffer =>

  private[this] val pipelineKey: String = "h2c prior knowledge initializer"

  override def connect(
    ctx: ChannelHandlerContext,
    remote: SocketAddress,
    local: SocketAddress,
    initialized: ChannelPromise
  ): Unit = {
    val connected = ctx.newPromise()

    // Cancel new promise if an original one is canceled.
    initialized.addListener(proxyCancellationsTo(connected, ctx))

    // Fail the original promise if a new one is failed.
    connected.addListener(proxyFailuresTo(initialized))

    connected.addListener(new ChannelFutureListener {
      override def operationComplete(future: ChannelFuture): Unit = {
        // We "try" because it might be already cancelled and we don't need to handle
        // cancellations here - it's already done by `proxyCancellationsTo`.
        if (future.isSuccess) {
          val _ = ctx.pipeline.addLast(new ChannelInboundHandlerAdapter { initializer =>
            override def channelActive(ctx: ChannelHandlerContext): Unit = {
              if (initialized.trySuccess()) {
                val _init = ctx.pipeline.remove(initializer)
                val _buff = ctx.pipeline.remove(buffer) // drains pending writes when removed
              }
            }

            override def exceptionCaught(ctx: ChannelHandlerContext, exn: Throwable): Unit = {
              initialized.tryFailure(exn)
              failPendingWrites(ctx, exn)
            }
          })
        } else {
          initialized.tryFailure(future.cause)
          failPendingWrites(ctx, future.cause)
        }
      }
    })

    val _connect = ctx.connect(remote, local, connected)
  }
}
