package io.buoyant.consul.v1

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class AgentApiTest extends FunSuite with Awaits with Exceptions {
  val localAgentBuf = Buf.Utf8("""{ "Config": { "Domain": "acme.co." } }""")
  var lastUri = ""

  def stubService(buf: Buf) = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.setContentTypeJson()
    rsp.content = buf
    rsp.headerMap.set("X-Consul-Index", "4")
    lastUri = req.uri
    Future.value(rsp)
  }

  test("localAgent returns LocalAgent instance") {
    val service = stubService(localAgentBuf)

    val result = await(AgentApi(service).localAgent())
    assert(result.Config.get.Domain.get == "acme.co.")
  }
}
