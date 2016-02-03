package io.buoyant.consul.v1

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Response, Request}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.consul.UnexpectedResponse
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ApiTest extends FunSuite with Awaits {
  val datacentersBuf = Buf.Utf8("""["dc1", "dc2"]""")
  val nodesBuf = Buf.Utf8("""[{"Node":"Sarahs-MBP-2","Address":"192.168.1.37","ServiceID":"hosted_web","ServiceName":"hosted_web","ServiceTags":["master"],"ServiceAddress":"","ServicePort":8084}]""")
  val mapBuf = Buf.Utf8("""{"consul":[],"hosted_web":["master"],"redis":[]}""")
  var lastUri = ""

  override val defaultWait = 2.seconds

  def stubService(buf: Buf) = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.setContentTypeJson()
    rsp.content = buf
    rsp.headerMap.set("X-Consul-Index", "4")
    lastUri = req.uri
    Future.value(rsp)
  }

  test("datacenters endpoint returns a seq of datacenter names") {
    val service = stubService(datacentersBuf)

    val response = await(Api(service).datacenters())
    assert(response.size == 2)
    assert(response.head == "dc1")
  }

  test("serviceNodes endpoint returns a seq of ServiceNodes") {
    val service = stubService(nodesBuf)

    val response = await(Api(service).serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("Sarahs-MBP-2"))
    assert(response.head.ServiceAddress == Some(""))
    assert(response.head.ServicePort == Some(8084))
  }

  test("serviceMap endpoint returns a map of serviceNames to tags") {
    val service = stubService(mapBuf)

    val response = await(Api(service).serviceMap()).value
    assert(response.get("consul") == Some(Seq.empty))
    assert(response.get("hosted_web") == Some(Seq("master")))
  }

  test("blocking index returned from one call can be used to set index on subsequent calls") {
    val service = stubService(mapBuf)
    val index = await(Api(service).serviceMap()).index.get

    await(Api(service).serviceMap(blockingIndex = Some(index)))
    assert(lastUri.contains(s"index=$index"))
  }

  test("propagates client failures") {
    val failureService = Service.mk[Request, Response] { req =>
      Future.exception(new Exception("I have no idea who to talk to"))
    }
    try {
      await(Api(failureService).serviceMap())
      assert(false)
    } catch {
      case e: Exception => assert(true)
    }
  }

  test("makes infinite retry attempts on retry = true") {
    var requestCount = 0;
    val failureService = Service.mk[Request, Response] { req =>
      requestCount = requestCount + 1
      if (requestCount > 1) {
        val rsp = Response()
        rsp.setContentTypeJson()
        rsp.content = datacentersBuf
        Future.value(rsp)
      } else {
        Future.exception(new Exception("I have no idea who to talk to"))
      }
    }
    val response = await(Api(failureService).datacenters(retry = true))
    assert(!response.isEmpty)
  }

  test("reports invalid datacenter as an unexpected response") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.content = Buf.Utf8("No path to datacenter")
      rsp.headerMap.set("X-Consul-Index", "0")
      rsp.setStatusCode(500) //weird that they return 500 for this
      lastUri = req.uri
      Future.value(rsp)
    }
    try {
      await(Api(failureService).serviceMap(datacenter = Some("non-existant dc")))
      assert(false)
    } catch {
      case e: UnexpectedResponse => assert(true)
    }
  }
}
