package io.buoyant.router.http2

import com.twitter.finagle.buoyant.http2.Http2Listener
import com.twitter.finagle.transport.Transport
import com.twitter.server.TwitterServer
import com.twitter.util.{Await, Return, Throw}
import io.netty.handler.codec.http2.Http2Frame
import java.net.InetSocketAddress

object Main extends TwitterServer {

  def main(): Unit = {

    val listener = Http2Listener.mk()
    val listening = listener.listen(new InetSocketAddress("127.1", 4142)) { trans =>
      def readLoop(): Unit = {
        log.info(s"Main: reading from ${trans.remoteAddress}")
        val _ = trans.read().respond {
          case Throw(e) =>
            log.error(e, "Main")
            val _ = trans.close()

          case Return(frame) =>
            log.info(s"Main: in: ${frame}")
            readLoop()
        }
      }

      readLoop()
    }

    log.info("Main: listening on 127.1:4142")
    closeOnExit(listening)
    Await.result(listening)
  }
}
