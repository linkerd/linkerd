package io.buoyant.namer.consul

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.consul.v1.ConsistencyMode
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import org.scalatest.FunSuite

class ConsulTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = ConsulConfig(None, None, None, None, None, None, None, None, None).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[ConsulInitializer]))
  }

  test("parse minimal config") {
    val yaml = s"""
                  |kind: io.l5d.consul
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulInitializer)))
    val consul = mapper.readValue[NamerConfig](yaml).asInstanceOf[ConsulConfig]
    assert(consul.setHost.isEmpty)
    assert(consul.includeTag.isEmpty)
    assert(!consul.disabled)
  }

  test("parse all options config") {
    val yaml = s"""
                    |kind: io.l5d.consul
                    |host: consul.site.biz
                    |port: 8600
                    |token: some-token
                    |includeTag: true
                    |setHost: true
                    |consistencyMode: stale
                    |failFast: true
                    |preferServiceAddress: false
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulInitializer)))
    val consul = mapper.readValue[NamerConfig](yaml).asInstanceOf[ConsulConfig]
    assert(consul.host == Some("consul.site.biz"))
    assert(consul.port == Some(Port(8600)))
    assert(consul.token == Some("some-token"))
    assert(consul.setHost == Some(true))
    assert(consul.includeTag == Some(true))
    assert(consul.consistencyMode == Some(ConsistencyMode.Stale))
    assert(consul.failFast == Some(true))
    assert(consul.preferServiceAddress == Some(false))
    assert(!consul.disabled)
  }
}
