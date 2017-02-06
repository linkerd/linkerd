package com.twitter.finagle.buoyant.h2.netty4

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.h2.param.Settings._
import io.netty.handler.codec.http2.Http2Settings

object Netty4H2Settings {
  def mk(params: Stack.Params): Http2Settings = {
    val s = new Http2Settings
    params[HeaderTableSize].size.foreach { n => s.headerTableSize(n.inBytes.toInt); () }
    params[InitialStreamWindowSize].size.foreach { n => s.initialWindowSize(n.inBytes.toInt); () }
    params[MaxConcurrentStreams].streams.foreach { n => s.maxConcurrentStreams(n); () }
    params[MaxFrameSize].size.foreach { n => s.maxFrameSize(n.inBytes.toInt); () }
    params[MaxHeaderListSize].size.foreach { n => s.maxHeaderListSize(n.inBytes.toInt); () }
    s
  }
}
