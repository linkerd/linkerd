package io.buoyant.linkerd.admin

import com.twitter.finagle.{Http => FHttp}
import com.twitter.finagle.http._
import com.twitter.finagle.{Status => _, _}
import com.twitter.server.TwitterServer
import io.buoyant.admin._
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class MetricsMainTest extends TwitterServer {
  val port = adminHttpServer.boundAddress.asInstanceOf[InetSocketAddress].getPort
  val client = FHttp.newService(s"localhost:$port")

  def main() {}
}

class MetricsQueryHandlerTest extends FunSuite with Awaits {

  test("GET request results in 200") {
    val web = new MetricsQueryHandler()
    val req = Request()
    req.uri = s"/admin/metrics"
    val rsp = await(web(req))
    assert(rsp.status == Status.Ok)
  }

  test("POST request results in 200") {
    val web = new MetricsQueryHandler()
    val req = Request()
    req.method = Method.Post
    req.uri = s"/admin/metrics"
    val rsp = await(web(req))
    assert(rsp.status == Status.Ok)
  }
}
