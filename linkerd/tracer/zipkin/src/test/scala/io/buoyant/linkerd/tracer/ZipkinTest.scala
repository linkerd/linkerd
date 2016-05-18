package io.buoyant.linkerd.tracer

import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.{TracerConfig, TracerInitializer}
import org.scalatest.FunSuite

class ZipkinTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = ZipkinConfig(None, None, None).newTracer()
  }

  test("service registration") {
    assert(LoadService[TracerInitializer]().exists(_.isInstanceOf[ZipkinTracerInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.zipkin
                  |host: foo
                  |port: 1234
                  |sampleRate: 0.5
                  |debugTrace: true
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ZipkinTracerInitializer)))
    val zipkin = mapper.readValue[TracerConfig](yaml).asInstanceOf[ZipkinConfig]
    assert(zipkin.host == Some("foo"))
    assert(zipkin.port == Some(1234))
    assert(zipkin.sampleRate == Some(0.5))
    assert(zipkin.debugTrace == true)
  }
}
