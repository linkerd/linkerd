package io.buoyant.namer.rancher

import com.twitter.finagle._
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.test.FunSuite
import org.scalatest.Matchers

class RancherTest extends FunSuite with Matchers {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = RancherConfig(
      portMappings = None,
      Some(30)
    ).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[RancherInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.rancher
                  |experimental: true
                  |portMappings:
                  |  proxy: 8080
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(RancherInitializer)))
    val config = mapper.readValue[NamerConfig](yaml).asInstanceOf[RancherConfig]
    assert(config.portMappings === Some(Map("proxy" -> 8080)))
    assert(config.disabled === false)
  }

  test("parse api response") {
    val json = s"""
      {
          "create_index": 1,
          "dns": [
              "169.254.169.250"
          ],
          "dns_search": [
              "sample-stack.rancher.internal",
              "sample-service1.sample-stack.rancher.internal",
              "rancher.internal"
          ],
          "environment_uuid": "6953974d-9b75-4534-ae87-58167ee1ffb3",
          "external_id": "4be2afd08ff32b77c060b5588ea91d37782d0d14e4cb45775a0abc2ca1a9486a",
          "health_check_hosts": [],
          "health_state": null,
          "host_uuid": "a19e45b8-afa3-44e6-8d16-896cb71f9b5d",
          "hostname": null,
          "ips": [
              "10.1.1.42"
          ],
          "labels": {
              "io.rancher.project.name": "sample-stack",
              "io.rancher.project_service.name": "sample-stack/sample-service1",
              "io.rancher.stack.name": "sample-stack",
              "io.rancher.stack_service.name": "sample-stack/sample-service1"
          },
          "links": {},
          "memory_reservation": null,
          "metadata_kind": "container",
          "milli_cpu_reservation": null,
          "name": "sample-stack-sample-service1-1",
          "network_from_container_uuid": null,
          "network_uuid": "03e258d3-d481-47ed-9b90-ef97f41d04d8",
          "ports": [
              "0.0.0.0:80:80/tcp"
          ],
          "primary_ip": "10.1.1.42",
          "primary_mac_address": "02:ee:64:23:90:90",
          "service_index": "1",
          "service_name": "sample-service1",
          "service_uuid": "18c60d0d-e60a-4ed1-a9fb-84f50a9c3b6f",
          "stack_name": "sample-stack",
          "stack_uuid": "5e45da87-3149-448c-8f64-44368e06101e",
          "start_count": 1,
          "state": "running",
          "system": false,
          "uuid": "0717ff00-af61-4786-8262-74daf1344f89"
      }
    """.stripMargin

    val response = RancherParser().readValue[RancherContainer](json)
    assert(response.createIndex === 1)
    assert(response.ips === List(
      "10.1.1.42"
    ))
    assert(response.primaryIp === "10.1.1.42")
    assert(response.labels === Map(
      "io.rancher.project.name" -> "sample-stack",
      "io.rancher.project_service.name" -> "sample-stack/sample-service1",
      "io.rancher.stack.name" -> "sample-stack",
      "io.rancher.stack_service.name" -> "sample-stack/sample-service1"
    ))
    assert(response.name === "sample-stack-sample-service1-1")
    assert(response.ports === List(
      PortMapping("0.0.0.0", 80, 80, "tcp")
    ))
    assert(response.stackName === "sample-stack")
    assert(response.serviceName === "sample-service1")
    assert(response.state === "running")
  }
}
