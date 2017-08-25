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
    val statuses = Some(Set(HealthStatus.Any))

    val response = await(HealthApi(service, statuses).serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("Sarahs-MBP-2"))
    assert(response.head.ServiceAddress == Some(""))
    assert(response.head.ServicePort == Some(8084))
  }

  test("serviceNodes endpoint supports consistency parameter") {
    val service = stubService(nodesBuf)
    val statuses = Some(Set(HealthStatus.Any))
    val api = HealthApi(service, statuses)

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
    val statuses = Some(Set(HealthStatus.Passing))

    val apiPassing = HealthApi(service, statuses)
    val responsePassing = await(apiPassing.serviceNodes("hosted_web")).value

    assert(responsePassing.size == 1)
    assert(responsePassing.head.ServiceName == Some("hosted_web"))
    assert(responsePassing.head.Node == Some("Sarahs-MBP-2"))

    val apiCritical = HealthApi(service, Some(Set(HealthStatus.Critical)))
    val responseCritical = await(apiCritical.serviceNodes("hosted_web")).value

    assert(responseCritical.size == 0)
  }

  test("HealthApi supports filtering by health status `any`") {
    val service = stubService(nodesWithChecksBuf)
    val statuses = Some(Set(HealthStatus.Any))
    val api = HealthApi(service, statuses)

    val response = await(api.serviceNodes("hosted_web")).value
    assert(response.size == 4)
  }

  test("HealthApi supports filtering by health status `passing`") {
    val service = stubService(nodesWithChecksBuf)
    val statuses = Some(Set(HealthStatus.Passing))
    val api = HealthApi(service, statuses)

    val response = await(api.serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("node-passing"))
    assert(response.head.Status == Some(HealthStatus.Passing))
  }

  test("HealthApi supports filtering by health status `warning`") {
    val service = stubService(nodesWithChecksBuf)
    val statuses = Some(Set(HealthStatus.Warning))
    val api = HealthApi(service, statuses)

    val response = await(api.serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("node-warning"))
    assert(response.head.Status == Some(HealthStatus.Warning))
  }

  test("HealthApi supports filtering by health status `critical`") {
    val service = stubService(nodesWithChecksBuf)
    val statuses = Some(Set(HealthStatus.Critical))
    val api = HealthApi(service, statuses)

    val response = await(api.serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("node-critical"))
    assert(response.head.Status == Some(HealthStatus.Critical))
  }

  test("HealthApi supports filtering by health status `maintenance`") {
    val service = stubService(nodesWithChecksBuf)
    val statuses = Some(Set(HealthStatus.Maintenance))
    val api = HealthApi(service, statuses)

    val response = await(api.serviceNodes("hosted_web")).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("node-maintenance"))
    assert(response.head.Status == Some(HealthStatus.Maintenance))
  }
}
