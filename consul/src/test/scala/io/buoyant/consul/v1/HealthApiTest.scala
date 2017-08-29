package io.buoyant.consul.v1

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HealthApiTest extends FunSuite with Awaits {
  val nodesBuf = Buf.Utf8("""[{"Node":{"Node":"Sarahs-MBP-2","Address":"192.168.1.37"}, "Service": {"Service":"hosted_web","Tags":["master"],"Port":8084, "Address":""}}]""")
  val nodesWithChecksBuf = Buf.Utf8("""[
    {"Node":{"Node":"node-passing"}, "Service": {"Service":"hosted_web"}, "Checks": [{"Status":"passing"}]},
    {"Node":{"Node":"node-warning"}, "Service": {"Service":"hosted_web"}, "Checks": [{"Status":"warning"}]},
    {"Node":{"Node":"node-critical"}, "Service": {"Service":"hosted_web"}, "Checks": [{"Status":"critical"}]},
    {"Node":{"Node":"node-maintenance"}, "Service": {"Service":"hosted_web"}, "Checks": [{"Status":"maintenance"}]}
  ]""")
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

    val response = await(HealthApi(service, Set(HealthStatus.Passing)).serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("Sarahs-MBP-2"))
    assert(response.head.ServiceAddress == Some(""))
    assert(response.head.ServicePort == Some(8084))
  }

  test("serviceNodes endpoint supports consistency parameter") {
    val service = stubService(nodesBuf)
    val api = HealthApi(service, Set(HealthStatus.Passing))

    await(api.serviceNodes("foo"))
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.serviceNodes("foo", consistency = Some(ConsistencyMode.Default)))
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.serviceNodes("foo", consistency = Some(ConsistencyMode.Stale)))
    assert(lastUri.contains("stale=true"))

    await(api.serviceNodes("foo", consistency = Some(ConsistencyMode.Consistent)))
    assert(lastUri.contains("consistent=true"))
  }

  test("Nodes without status are filtered as if `passing`") {
    val service = stubService(nodesBuf)

    val apiPassing = HealthApi(service, Set(HealthStatus.Passing))
    val responsePassing = await(apiPassing.serviceNodes("hosted_web")).value

    assert(responsePassing.size == 1)
    assert(responsePassing.head.ServiceName == Some("hosted_web"))
    assert(responsePassing.head.Node == Some("Sarahs-MBP-2"))

    val apiCritical = HealthApi(service, Set(HealthStatus.Critical))
    val responseCritical = await(apiCritical.serviceNodes("hosted_web")).value

    assert(responseCritical.size == 0)
  }

  test("HealthApi supports filtering by health status `warning`") {
    val service = stubService(nodesWithChecksBuf)
    val api = HealthApi(service, Set(HealthStatus.Warning))

    val response = await(api.serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("node-warning"))
    assert(response.head.Status == Some(HealthStatus.Warning))
  }

  test("HealthApi supports filtering by health status `critical`") {
    val service = stubService(nodesWithChecksBuf)
    val api = HealthApi(service, Set(HealthStatus.Critical))

    val response = await(api.serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("node-critical"))
    assert(response.head.Status == Some(HealthStatus.Critical))
  }

  test("HealthApi supports filtering by health status `maintenance`") {
    val service = stubService(nodesWithChecksBuf)
    val api = HealthApi(service, Set(HealthStatus.Maintenance))

    val response = await(api.serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("node-maintenance"))
    assert(response.head.Status == Some(HealthStatus.Maintenance))
  }
}
