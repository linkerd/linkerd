package io.buoyant.consul.v1

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class CatalogApiTest extends FunSuite with Awaits with Exceptions {
  val datacentersBuf = Buf.Utf8("""["dc1", "dc2"]""")
  val nodesBuf = Buf.Utf8("""[{"Node":"Sarahs-MBP-2","Address":"192.168.1.37","ServiceID":"hosted_web","ServiceName":"hosted_web","ServiceTags":["master"],"ServiceAddress":"","ServicePort":8084, "ServiceMeta":{"serv_meta":"some_serv_meta"}, "NodeMeta":{"node_meta":"some_node_meta"}}]""")
  val mapBuf = Buf.Utf8("""{"consul":[],"hosted_web":["master"],"redis":[]}""")
  var lastUri = ""

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

    val response = await(CatalogApi(service).datacenters()())
    assert(response.size == 2)
    assert(response.head == "dc1")
  }

  test("serviceNodes endpoint returns a seq of ServiceNodes") {
    val service = stubService(nodesBuf)

    val response = await(CatalogApi(service).serviceNodes("hosted_web")()).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("Sarahs-MBP-2"))
    assert(response.head.ServiceMeta == Some(Map("serv_meta" -> "some_serv_meta")))
    assert(response.head.NodeMeta == Some(Map("node_meta" -> "some_node_meta")))
    assert(response.head.ServiceAddress == Some(""))
    assert(response.head.ServicePort == Some(8084))
  }

  test("serviceNodes endpoint for empty response") {
    val service = stubService(Buf.Utf8("[]"))
    val response = await(CatalogApi(service).serviceNodes("foo")()).value
    assert(response.isEmpty)
  }

  test("serviceNodes endpoint supports consistency parameter") {
    val service = stubService(nodesBuf)
    val api = CatalogApi(service)

    await(api.serviceNodes("hosted_web")())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.serviceNodes("hosted_web", consistency = Some(ConsistencyMode.Default))())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.serviceNodes("hosted_web", consistency = Some(ConsistencyMode.Stale))())
    assert(lastUri.contains("stale=true"))

    await(api.serviceNodes("hosted_web", consistency = Some(ConsistencyMode.Consistent))())
    assert(lastUri.contains("consistent=true"))
  }

  test("serviceMap endpoint returns a map of serviceNames to tags") {
    val service = stubService(mapBuf)

    val response = await(CatalogApi(service).serviceMap()()).value
    assert(response.get("consul") == Some(Seq.empty))
    assert(response.get("hosted_web") == Some(Seq("master")))
  }

  test("serviceMap endpoint supports consistency parameter") {
    val service = stubService(mapBuf)
    val api = CatalogApi(service)

    await(api.serviceMap()())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.serviceMap(consistency = Some(ConsistencyMode.Default))())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.serviceMap(consistency = Some(ConsistencyMode.Stale))())
    assert(lastUri.contains("stale=true"))

    await(api.serviceMap(consistency = Some(ConsistencyMode.Consistent))())
    assert(lastUri.contains("consistent=true"))
  }

  test("blocking index returned from one call can be used to set index on subsequent calls") {
    val service = stubService(mapBuf)
    val index = await(CatalogApi(service).serviceMap()()).index.get

    await(CatalogApi(service).serviceMap(blockingIndex = Some(index))())
    assert(lastUri.contains(s"index=$index"))
  }

  test("propagates client failures") {
    val failureService = Service.mk[Request, Response] { req =>
      Future.exception(new Exception("I have no idea who to talk to"))
    }
    assertThrows[Exception](
      await(CatalogApi(failureService).serviceMap()())
    )
  }

  test("makes infinite retry attempts on retry = true") {
    var requestCount = 0
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
    val response = await(CatalogApi(failureService).datacenters(retry = true)())
    assert(response.nonEmpty)
  }

  test("reports invalid datacenter as an unexpected response") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.content = Buf.Utf8("No path to datacenter")
      rsp.headerMap.set("X-Consul-Index", "0")
      rsp.statusCode = 500 //weird that they return 500 for this
      lastUri = req.uri
      Future.value(rsp)
    }
    assertThrows[UnexpectedResponse](
      await(CatalogApi(failureService).serviceMap(datacenter = Some("non-existent dc"))())
    )
  }
}
