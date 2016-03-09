package io.l5d.experimental

import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Path, Stack}
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.config.types.Port
import io.buoyant.linkerd.{NamerConfig, NamerInitializer}
import org.scalatest.FunSuite

class MarathonTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    marathon(None, None, None).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[MarathonInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind:      io.l5d.experimental.marathon
                  |prefix:    /io.l5d.marathon
                  |host:      marathon.mesos
                  |port:      80
                  |uriPrefix: /marathon
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Seq(MarathonInitializer))
    val marathon = mapper.readValue[NamerConfig](yaml).asInstanceOf[marathon]
    assert(marathon.host == Some("marathon.mesos"))
    assert(marathon.port == Some(Port(80)))
    assert(marathon.uriPrefix == Some("/marathon"))
    assert(marathon._prefix == Some(Path.read("/io.l5d.marathon")))
  }
}
