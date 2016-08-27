package io.buoyant.router

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2.{Request, Response, DataStream}
import com.twitter.io.Buf
import com.twitter.util.{Closable, Future, Promise, Time}
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class H2EndToEndTest extends FunSuite with Awaits {
  test("request response from a client to a server") {

    val rspP = new Promise[Unit]
    val echo = Service.mk[Request, Response] { req =>
      val rsp = new Response with DataStream.Proxy {
        val status = 200
        val headers = Seq(":status" -> "200")
        val dataStream = req
      }
      rspP.map(_ => rsp)
    }
    val server = H2.serve(new InetSocketAddress("127.1", 0), echo)
    val port = server.boundAddress.asInstanceOf[InetSocketAddress].getPort
    val client = H2.newService(s"/$$/inet/127.1/$port")
    try {
      // XXX this clearly needs more work ot be user-friendly; not a
      // current goal, though.
      val reqQ = new AsyncQueue[DataStream.Frame]
      val req = new Request {
        val scheme = "h2"
        val authority = "greeter"
        val method = "yo"
        val path = "/fir"
        val headers = Seq(
          ":scheme" -> "h2",
          ":authority" -> "greeter",
          ":method" -> "yo",
          ":path" -> "/fir"
        )
        def isEmpty = false // XXX not totally accurate but whatever
        val endP = new Promise[Unit]
        def read() = reqQ.poll().onSuccess { f =>
          if (f.isEnd) {
            val _ = endP.setDone()
          }
        }
        def onEnd = endP
        def fail(e: Throwable) = reqQ.fail(e)
      }
      val rspf = client(req)
      assert(!rspf.isDefined)
      rspP.setDone()
      val rsp = await(rspf)
    } finally await(Closable.all(server, client).close())
  }
}
