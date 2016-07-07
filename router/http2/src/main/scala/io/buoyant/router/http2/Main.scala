package io.buoyant.router.http2

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.http2._
import com.twitter.server.TwitterServer
import com.twitter.util.{Await, Future}
import io.buoyant.router.Http2
import java.net.InetSocketAddress

object Main extends TwitterServer {

  def main(): Unit = {
    val addr = new InetSocketAddress("127.1", 4142)

    val gen = Http2.newService("/$/inet/127.1/8181")
    closeOnExit(gen)
    val word = Http2.newService("/$/inet/127.1/8282")
    closeOnExit(word)

    val service = Service.mk[Request, Response] {
      case req if req.headers.path.startsWith("/proto.GenSvc") => gen(req)
      case req if req.headers.path.startsWith("/proto.WordSvc") => word(req)
      case _ => Future.value(Response(ResponseHeaders(404)))
    }

    log.info(s"Main: listening on $addr")
    val server = Http2.serve(addr, service)
    closeOnExit(server)
    Await.result(server)
  }

}
