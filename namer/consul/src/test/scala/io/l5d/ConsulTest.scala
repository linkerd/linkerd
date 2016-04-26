package io.l5d.experimental

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.config.Parser
import org.scalatest.FunSuite

class ConsulTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = consul(None, None).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[ConsulInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                    |kind: io.l5d.experimental.consul
                    |host: consul.site.biz
                    |port: 8600
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulInitializer)))
    val consul = mapper.readValue[NamerConfig](yaml).asInstanceOf[consul]
    assert(consul.host == Some("consul.site.biz"))
    assert(consul.port == Some(Port(8600)))
  }
}
