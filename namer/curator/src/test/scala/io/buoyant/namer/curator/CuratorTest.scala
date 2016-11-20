package io.buoyant.namer.curator

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.{HostAndPort, Port}
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import org.scalatest.FunSuite

class CuratorTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = CuratorConfig(
      Seq(HostAndPort(Some("localhost"), Some(Port(2181)))),
      Some("/curator")
    ).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[CuratorInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.curator
                  |experimental: true
                  |zkAddrs:
                  |- host: localhost
                  |  port: 2181
                  |basePath: /curator
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(CuratorInitializer)))
    val curator = mapper.readValue[NamerConfig](yaml).asInstanceOf[CuratorConfig]
    assert(curator.connectString == "localhost:2181")
    assert(curator.basePath == Some("/curator"))
    assert(!curator.disabled)
  }
}
