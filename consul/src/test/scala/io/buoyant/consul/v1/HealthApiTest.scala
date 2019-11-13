package io.buoyant.consul.v1

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HealthApiTest extends FunSuite with Awaits {
  val nodesBuf = Buf.Utf8("""[{"Node":{"Node":"Sarahs-MBP-2","Address":"192.168.1.37", "Meta": {"node_meta":"some_node_meta"}}, "Service": {"Service":"hosted_web","Tags":["master"],"Port":8084, "Address":"", "Meta": {"serv_meta":"some_serv_meta"}}}]""")
  val nodesWithChecksBuf = Buf.Utf8("""[
    {"Node":{"Node":"node-passing", "Meta": {"node_meta":"some_node_meta"}}, "Service": {"Service":"hosted_web", "Meta": {"serv_meta":"some_serv_meta"}}, "Checks": [{"Status":"passing"}]},
    {"Node":{"Node":"node-warning", "Meta": {"node_meta":"some_node_meta"}}, "Service": {"Service":"hosted_web", "Meta": {"serv_meta":"some_serv_meta"}}, "Checks": [{"Status":"warning"}]},
    {"Node":{"Node":"node-critical", "Meta": {"node_meta":"some_node_meta"}}, "Service": {"Service":"hosted_web", "Meta": {"serv_meta":"some_serv_meta"}}, "Checks": [{"Status":"critical"}]},
    {"Node":{"Node":"node-maintenance", "Meta": {"node_meta":"some_node_meta"}}, "Service": {"Service":"hosted_web", "Meta": {"serv_meta":"some_serv_meta"}}, "Checks": [{"Status":"maintenance"}]}
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

    val response = await(HealthApi(service, Set(HealthStatus.Passing)).serviceNodes("hosted_web")()).value
    assert(response.size == 1)
    assert(response.head.ServiceName == Some("hosted_web"))
    assert(response.head.Node == Some("Sarahs-MBP-2"))
    assert(response.head.ServiceAddress == Some(""))
    assert(response.head.ServiceMeta == Some(Map("serv_meta" -> "some_serv_meta")))
    assert(response.head.NodeMeta == Some(Map("node_meta" -> "some_node_meta")))
    assert(response.head.ServicePort == Some(8084))
  }

  test("serviceNodes endpoint supports consistency parameter") {
    val service = stubService(nodesBuf)
    val api = HealthApi(service, Set(HealthStatus.Passing))

    await(api.serviceNodes("foo")())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.serviceNodes("foo", consistency = Some(ConsistencyMode.Default))())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.serviceNodes("foo", consistency = Some(ConsistencyMode.Stale))())
    assert(lastUri.contains("stale=true"))

    await(api.serviceNodes("foo", consistency = Some(ConsistencyMode.Consistent))())
    assert(lastUri.contains("consistent=true"))
  }

  test("Nodes without status are filtered as if `passing`") {
    val service = stubService(nodesBuf)

    val apiPassing = HealthApi(service, Set(HealthStatus.Passing))
    val responsePassing = await(apiPassing.serviceNodes("hosted_web")()).value

    assert(responsePassing.size == 1)
    assert(responsePassing.head.ServiceName == Some("hosted_web"))
    assert(responsePassing.head.Node == Some("Sarahs-MBP-2"))

    val apiCritical = HealthApi(service, Set(HealthStatus.Critical))
    val responseCritical = await(apiCritical.serviceNodes("hosted_web")()).value

    assert(responseCritical.size == 0)
  }

  def filterTest(status: HealthStatus.Value, name: String) =
    test(s"HealthApi supports filtering by health status `$name`") {
      val service = stubService(nodesWithChecksBuf)
      val api = HealthApi(service, Set(status))

      val response = await(api.serviceNodes("hosted_web")()).value
      assert(response.size == 1)
      assert(response.head.ServiceName == Some("hosted_web"))
      assert(response.head.Node == Some(s"node-$name"))
      assert(response.head.Status == Some(status))
    }

  // No filterTest for health status `passing` as HealthApi is optimized to use
  // consul API parameter `passing=true` to perform server side filtering when
  // only passing nodes are required.
  filterTest(HealthStatus.Warning, "warning")
  filterTest(HealthStatus.Critical, "critical")
  filterTest(HealthStatus.Maintenance, "maintenance")
}
gir