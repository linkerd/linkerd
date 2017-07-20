package io.buoyant.namer.marathon

import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Path, Stack}
import com.twitter.conversions.time._
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import org.scalatest.FunSuite

class MarathonTest extends FunSuite {
  // minimum number of unique TTLs to generate randomly.
  //
  // we're testing this by generating 300 jittered TTL durations and asserting
  // that it contains at least this many distinct unique values.
  //
  // since this is probabilistic, we probably want to be fairly conservative
  // here...it *should* be producing around 95 unique values out of every 300,
  // and if it's not, that might be a bad sign, but we're lowballing the
  // expected minimum amount so that we don't see tests breaking due to
  // something possible but astronomically improbable. ah, the perils of
  // (pseudo)random number generation!
  //
  // change this value if you disagree with my rationale.
  val MinUniqueTTLs = 2

  test("sanity") {
    // ensure it doesn't totally blowup
    // We use a name that resolves here
    val _ = MarathonConfig(Some("localhost"), None, None, None, None, None,
      None, None).newNamer(Stack.Params.empty)
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

  test("parse config with tls") {
    val yaml = s"""
                  |kind:           io.l5d.marathon
                  |prefix:         /io.l5d.marathon
                  |host:           localhost
                  |port:           80
                  |uriPrefix:      /marathon
                  |ttlMs:          300
                  |useHealthCheck: false
                  |tls:
                  |  disableValidation: false
                  |  commonName: master.mesos
                  |  trustCerts:
                  |    - /foo/caCert.pem
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(MarathonInitializer)))
    val marathon = mapper.readValue[NamerConfig](yaml).asInstanceOf[MarathonConfig]
    assert(marathon.host.contains("localhost"))
    assert(marathon.port.contains(Port(80)))
    assert(marathon.uriPrefix.contains("/marathon"))
    assert(marathon._prefix.contains(Path.read("/io.l5d.marathon")))
    assert(marathon.ttlMs.contains(300))
    assert(marathon.jitterMs.isEmpty)

    val tls = marathon.tls.get
    assert(tls.disableValidation.contains(false))
    assert(tls.commonName.contains("master.mesos"))
    assert(tls.trustCerts.contains(List("/foo/caCert.pem")))

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

    val baseTtl = marathon.ttlMs.get.millis
    val minTtl = baseTtl - 50.millis
    val maxTtl = baseTtl + 50.millis

    for (jitter <- jitters.take(300)) {
      assert(jitter >= minTtl)
      assert(jitter <= maxTtl)
    }

    // assert that we're generating a reasonable amount of distinct TTLs –
    // see above for what constitutes "reasonable"
    assert(jitters.take(300).toSet.size >= MinUniqueTTLs)

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

    val baseTtl = marathon.ttlMs.get.millis
    val minTtl = baseTtl - 100.millis
    val maxTtl = baseTtl + 100.millis

    for (jitter <- jitters.take(300)) {
      assert(jitter >= minTtl)
      assert(jitter <= maxTtl)
    }

    // assert that we're generating a reasonable amount of distinct TTLs –
    // see above for what constitutes "reasonable"
    assert(jitters.take(300).toSet.size >= MinUniqueTTLs)

  }
}
