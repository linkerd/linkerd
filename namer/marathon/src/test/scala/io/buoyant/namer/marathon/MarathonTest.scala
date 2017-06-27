package io.buoyant.namer.marathon

import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Path, Stack}
import com.twitter.conversions.time._
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import org.scalatest.FunSuite

class MarathonTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    // We use a name that resolves here
    val _ = MarathonConfig(Some("localhost"), None, None, None, None, None,
      None).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[MarathonInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind:           io.l5d.marathon
                  |prefix:         /io.l5d.marathon
                  |host:           localhost
                  |port:           80
                  |uriPrefix:      /marathon
                  |ttlMs:          300
                  |jitterMs:       60
                  |useHealthCheck: false
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(MarathonInitializer)))
    val marathon = mapper.readValue[NamerConfig](yaml).asInstanceOf[MarathonConfig]
    assert(marathon.host.contains("localhost"))
    assert(marathon.port.contains(Port(80)))
    assert(marathon.uriPrefix.contains("/marathon"))
    assert(marathon._prefix.contains(Path.read("/io.l5d.marathon")))
    assert(marathon.ttlMs.contains(300))
    assert(marathon.jitterMs.contains(60))
    assert(!marathon.disabled)
  }

  test("parse config with default jitter") {
    val yaml = s"""
                  |kind:           io.l5d.marathon
                  |prefix:         /io.l5d.marathon
                  |host:           localhost
                  |port:           80
                  |uriPrefix:      /marathon
                  |ttlMs:          300
                  |useHealthCheck: false
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(MarathonInitializer)))
    val marathon = mapper.readValue[NamerConfig](yaml).asInstanceOf[MarathonConfig]
    assert(marathon.host.contains("localhost"))
    assert(marathon.port.contains(Port(80)))
    assert(marathon.uriPrefix.contains("/marathon"))
    assert(marathon._prefix.contains(Path.read("/io.l5d.marathon")))
    assert(marathon.ttlMs.contains(300))
    assert(marathon.jitterMs.isEmpty)
    assert(!marathon.disabled)
  }

  test("jitter TTLs with the default jitter") {
    val yaml = s"""
                  |kind:           io.l5d.marathon
                  |prefix:         /io.l5d.marathon
                  |host:           localhost
                  |port:           80
                  |uriPrefix:      /marathon
                  |ttlMs:          300
                  |useHealthCheck: false
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(MarathonInitializer)))
    val marathon = mapper.readValue[NamerConfig](yaml).asInstanceOf[MarathonConfig]
    val jitters = Stream.continually(marathon.nextTtl)
    val minTtl = marathon.ttlMs.get.millis - 50.millis
    val maxTtl = minTtl + 50.millis

    for (jitter <- jitters.take(300)) {
      assert(jitter >= minTtl)
      assert(jitter <= maxTtl)
    }

    // assert that at least half of the jittered amounts are distinct
    assert(jitters.take(300).toSet.size >= 150)

  }

  test("jitter TTLs with a set jitter") {
    val yaml = s"""
                  |kind:           io.l5d.marathon
                  |prefix:         /io.l5d.marathon
                  |host:           localhost
                  |port:           80
                  |uriPrefix:      /marathon
                  |ttlMs:          300
                  |jitterMs:       100
                  |useHealthCheck: false
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(MarathonInitializer)))
    val marathon = mapper.readValue[NamerConfig](yaml).asInstanceOf[MarathonConfig]
    val jitters = Stream.continually(marathon.nextTtl)
    val minTtl = marathon.ttlMs.get.millis - 100.millis
    val maxTtl = minTtl + 100.millis

    for (jitter <- jitters.take(300)) {
      assert(jitter >= minTtl)
      assert(jitter <= maxTtl)
    }

    // assert that at least half of the jittered amounts are distinct
    assert(jitters.take(300).toSet.size >= 150)

  }
}

