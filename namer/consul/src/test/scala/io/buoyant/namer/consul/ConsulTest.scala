package io.buoyant.namer.consul

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import org.scalatest.FunSuite

class ConsulTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = ConsulConfig(None, None).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[ConsulInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                    |kind: io.l5d.consul
                    |experimental: true
                    |host: consul.site.biz
                    |port: 8600
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulInitializer)))
    val consul = mapper.readValue[NamerConfig](yaml).asInstanceOf[ConsulConfig]
    assert(consul.host == Some("consul.site.biz"))
    assert(consul.port == Some(Port(8600)))
    assert(!consul.disabled)
  }

  test("parse config without experimental param") {
    val yaml = s"""
                  |kind: io.l5d.consul
                  |host: consul.site.biz
                  |port: 8600
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulInitializer)))
    val consul = mapper.readValue[NamerConfig](yaml).asInstanceOf[ConsulConfig]
    assert(consul.host == Some("consul.site.biz"))
    assert(consul.port == Some(Port(8600)))
    assert(consul.disabled)
  }
}
