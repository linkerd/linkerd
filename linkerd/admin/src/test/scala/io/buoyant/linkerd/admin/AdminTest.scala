package io.buoyant.linkerd.admin

import com.twitter.finagle.{Http => FHttp, Service}
import com.twitter.finagle.http.{Request, Status}
import com.twitter.server.TwitterServer
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class AdminHandlerTest extends FunSuite {
  test("handles empty content") {
    assert(AdminHandler.adminHtml("").contains("linkerd admin"))
  }

  test("handles populated params") {
    val content = "fake content"
    val tailContent = "fake tail content"
    val javaScripts = Seq("foo.js", "bar.js")
    val csses = Seq("foo.css", "bar.css")

    val html = AdminHandler.adminHtml(
      content = content,
      tailContent = tailContent,
      javaScripts = javaScripts,
      csses = csses
    )

    assert(html.contains(content))
    assert(html.contains(tailContent))
    javaScripts.foreach { js =>
      assert(html.contains(s"""js/$js"""))
    }
    csses.foreach { css =>
      assert(html.contains(s"""css/$css"""))
    }
  }
}

class FlagsHandlerTest extends FunSuite with Awaits {
  test("serves ok on /flags") {
    val rsp = await(new FlagsHandler("")(Request("/flags")))
    assert(rsp.status == Status.Ok)
  }

  test("serves flag data passed to it") {
    val rsp = await(new FlagsHandler("foo")(Request("/flags")))
    assert(rsp.contentString.contains("foo"))
  }
}

class BuoyantMainTest extends TwitterServer with LinkerdAdmin {
  val port = adminHttpServer.boundAddress.asInstanceOf[InetSocketAddress].getPort
  val client = FHttp.newService(s"localhost:$port")

  def main() {}
}

class AdminTest extends FunSuite with Awaits {

  test("serves buoyant admin at /")(new BuoyantMainTest {
    override def main() {
      val rsp = await(client(Request("/")))
      assert(rsp.status == Status.Ok)
    }
  })

  test("serves buoyant static files at /files")(new BuoyantMainTest {
    override def main() {
      val rsp = await(client(Request("/files/css/admin.css")))
      assert(rsp.status == Status.Ok)
    }
  })

  test("serves 404 for a non-existent route")(new BuoyantMainTest {
    override def main() {
      val rsp = await(client(Request("/foo")))
      assert(rsp.status == Status.NotFound)
    }
  })

  test("serves twitter-server admin at /admin")(new BuoyantMainTest {
    override def main() {
      val rsp = await(client(Request("/admin")))
      assert(rsp.status == Status.Ok)
    }
  })

  test("serves twitter-server static files at /admin/files")(new BuoyantMainTest {
    override def main() {
      val rsp = await(client(Request("/admin/files/css/summary.css")))
      assert(rsp.status == Status.Ok)
    }
  })

  test("serves /admin/metrics.json")(new BuoyantMainTest {
    override def main() {
      val rsp = await(client(Request("/admin/metrics.json")))
      assert(rsp.status == Status.Ok)
    }
  })
}