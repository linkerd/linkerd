package io.buoyant.consul.v1

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HealthApiTest extends FunSuite with Awaits {
  val nodesBuf = Buf.Utf8("""[{"Node":{"Node":"Sarahs-MBP-2","Address":"192.168.1.37"}, "Service": {"Service":"hosted_web","Tags":["master"],"Port":8084, "Address":""}}]""")
  var lastUri = ""

  def stubService(buf: Buf) = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.setContentTypeJson()
    rsp.content = buf
    rsp.headerMap.set("X-Consul-Index", "4")
    lastUri = req.uri
    Future.value(rsp)
  }

  test("serviceNodes endpoint returns a seq of ServiceNodes") {
    val service = stubService(nodesBuf)

    val response = await(HealthApi(service).serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("Sarahs-MBP-2"))
    assert(response.head.ServiceAddress == Some(""))
    assert(response.head.ServicePort == Some(8084))
  }
}
