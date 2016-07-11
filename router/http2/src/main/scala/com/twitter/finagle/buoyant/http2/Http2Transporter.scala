package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.buoyant.Http2PriorKnowledgeInitHandler
import com.twitter.finagle.transport.TransportProxy
import com.twitter.io.Charsets
import io.netty.channel._
import io.netty.handler.codec.http2._
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean

object Http2Transporter {

  def mk(params0: Stack.Params): Transporter[Http2StreamFrame, Http2StreamFrame] = {
    val initializer = { cp: ChannelPipeline =>
      val _wireDebug = cp.addLast("wire debug", new LoggingHandler(LogLevel.INFO))

      // val _h2 = cp.addLast("h2", new Http2Connector)

      val _h2 = cp.addLast("h2", new Http2FrameCodec(false /*server*/ ))
      val _h2FramerDebug = cp.addLast("h2 framer debug", new DebugHandler("client[framer]"))

      // Buffer writes until the channel is marked active (i.e. the
      // protocol has been initialized).
      val _writeBuffer = cp.addLast("buffered connect delay", new Http2PriorKnowledgeInitHandler)

      val _h2Debug = cp.addLast("h2 debug", new DebugHandler("client[h2]"))
    }

    // Netty4's Http2 Codec doesn't support backpressure yet.
    // See https://github.com/netty/netty/issues/3667#issue-69640214
    val params = params0 + Netty4Transporter.Backpressure(false)

    Netty4Transporter(initializer, params)
  }

  // def mkConnector(): Http2ConnectionHandler = new Http2Connector
  // private class Http2Connector extends Http2ConnectionHandler {
  //   def this(conn: Http2Connection) = {
  //     val writer = new DefaultHttp2FrameWriter
  //     val encoder = new DefaultHttp2ConnectionEncoder(conn, writer)

  //     val reader = new DefaultHttp2FrameReader
  //     val decoder = new DefaultHttp2ConnectionDecoder(conn, encoder, reader)

  //     val settings = new Http2Settings().pushEnabled(false)
  //     super(decoder, encoder, initialSettings)
  //   }
  // }
}
