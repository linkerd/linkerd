package io.buoyant.router.http2

import com.twitter.finagle.{Service, Stack}
import com.twitter.finagle.buoyant.http2._
import com.twitter.finagle.transport.Transport
import com.twitter.server.TwitterServer
import com.twitter.util.{Await, Future}
import io.netty.handler.codec.http2.Http2StreamFrame
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

object Main extends TwitterServer {

  def main(): Unit = {
    val addr = new InetSocketAddress("127.1", 4142)

    val listener = Http2Listener.mk(Stack.Params.empty)

    val service = Service.mk[Request, Response] { req =>
      Future.exception(new Exception("ughit"))
    }

    log.info(s"Main: listening on $addr")
    val listening = listener.listen(addr) { transport: Transport[Http2StreamFrame, Http2StreamFrame] =>
      val _ = ServerDispatcher.dispatch(transport, service)
    }
    closeOnExit(listening)
    Await.result(listening)
  }

}
