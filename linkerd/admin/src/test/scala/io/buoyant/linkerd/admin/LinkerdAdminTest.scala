package io.buoyant.linkerd.admin

import com.twitter.finagle.{Http => FHttp}
import com.twitter.finagle.http.{Request, Status}
import com.twitter.server.TwitterServer
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class BuoyantMainTest extends TwitterServer {
  val port = adminHttpServer.boundAddress.asInstanceOf[InetSocketAddress].getPort
  val client = FHttp.newService(s"localhost:$port")

  def main() {}
}

// TODO: these tests are not getting run
class LinkerdAdminTest extends FunSuite with Awaits {

  test("serves buoyant admin at /") {
    val _ = new BuoyantMainTest {
      override def main() {
        val rsp = await(client(Request("/")))
        assert(rsp.status == Status.Ok)
        ()
      }
    }
  }

  test("serves buoyant static files at files") {
    val _ = new BuoyantMainTest {
      override def main() {
        val rsp = await(client(Request("files/css/dashboard.css")))
        assert(rsp.status == Status.Ok)
        ()
      }
    }
  }

  test("serves 404 for a non-existent route") {
    val _ = new BuoyantMainTest {
      override def main() {
        val rsp = await(client(Request("foo")))
        assert(rsp.status == Status.NotFound)
        ()
      }
    }
  }

  test("serves twitter-server admin at admin") {
    val _ = new BuoyantMainTest {
      override def main() {
        val rsp = await(client(Request("admin")))
        assert(rsp.status == Status.Ok)
        ()
      }
    }
  }

  test("serves twitter-server static files at admin/files") {
    val _ = new BuoyantMainTest {
      override def main() {
        val rsp = await(client(Request("admin/files/css/dashboard.css")))
        assert(rsp.status == Status.Ok)
        ()
      }
    }
  }

  test("serves admin/metrics.json") {
    val _ = new BuoyantMainTest {
      override def main() {
        val rsp = await(client(Request("admin/metrics.json")))
        assert(rsp.status == Status.Ok)
        ()
      }
    }
  }
}
