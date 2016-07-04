package io.buoyant.router.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.http2.Http2Listener
import com.twitter.finagle.transport.Transport
import com.twitter.server.TwitterServer
import com.twitter.util.{Await, Return, Throw}
import io.netty.handler.codec.http2.Http2StreamFrame
import java.net.InetSocketAddress

object Main extends TwitterServer {

  def main(): Unit = {
    val params = Stack.Params.empty +
      Transport.Verbose(true)

    val listener = Http2Listener.mk(params)

    log.info("Main: listening on 127.1:4142")
    val listening = listener.listen(new InetSocketAddress("127.1", 4142))(transporter)
    closeOnExit(listening)
    Await.result(listening)
  }

  def transporter[I, O] = { transport: Transport[I, O] =>
    def readLoop(): Unit = {
      log.info(s"Main: ${transport.remoteAddress} reading")

      val _ = transport.read().respond {
        case Throw(e) =>
          log.error(e, s"Main: ${transport.remoteAddress} error")
          val _ = transport.close()

        case Return(frame) =>
          log.info(s"Main: ${transport.remoteAddress} read ${frame}")
          readLoop()
      }
    }

    readLoop()
  }

}
