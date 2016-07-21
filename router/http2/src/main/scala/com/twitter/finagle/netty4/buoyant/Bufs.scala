package com.twitter.finagle.netty4
package buoyant

import com.twitter.finagle.Stack
import io.netty.buffer.ByteBufAllocator

object Bufs {
  def allocator(params: Stack.Params): ByteBufAllocator = {
    val param.Allocator(allocator) = params[param.Allocator]
    allocator
  }
}
