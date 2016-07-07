package io.buoyant.router.http2

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.http2._
import com.twitter.server.TwitterServer
import com.twitter.util.{Await, Future}
import io.buoyant.router.Http2
import java.net.InetSocketAddress

object Main extends TwitterServer {

  private val service = Service.mk[Request, Response] { req =>
    log.info(s"Main: service $req")
    Future.value(Response(ResponseHeaders(237)))
  }

  def main(): Unit = {
    val addr = new InetSocketAddress("127.1", 4142)

    log.info(s"Main: listening on $addr")
    val server = Http2.serve(addr, service)
    closeOnExit(server)
    Await.result(server)
  }

}
