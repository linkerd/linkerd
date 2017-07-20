package io.buoyant.linkerd

import com.twitter.finagle.param
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.tracing._
import io.buoyant.config.{ConflictingLabels, ConflictingPorts, ConflictingSubtypes}
import io.buoyant.namer.Param.Namers
import io.buoyant.namer.{ConflictingNamerInitializer, NamerInitializer, TestNamer, TestNamerInitializer}
import io.buoyant.telemetry._
import io.buoyant.test.Exceptions
import java.net.{InetAddress, InetSocketAddress}
import org.scalatest.FunSuite

class LinkerTest extends FunSuite with Exceptions {

  def initializer(
    protos: Seq[ProtocolInitializer] = Seq(TestProtocol.Plain, TestProtocol.Fancy),
    namers: Seq[NamerInitializer] = Seq(TestNamerInitializer),
    telemeters: Seq[TelemeterInitializer] = Seq(new TestTelemeterInitializer)
  ) = Linker.Initializers(protocol = protos, namer = namers, telemetry = telemeters)

  def parse(yaml: String) = initializer().load(yaml)

  test("basic") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |- protocol: fancy
         |  experimental: true
         |  servers:
         |  - port: 2
         |""".stripMargin
    val linker = parse(yaml)
    val routers = linker.routers

    val Namers(namers) = linker.routers.head.params[Namers]
    assert(namers == Nil)
    assert(linker.namers == Nil)

    assert(routers.size == 2)

    assert(routers(0).label == "plain")
    assert(routers(0).protocol == TestProtocol.Plain)
    assert(routers(0).servers.size == 1)
    assert(routers(0).servers(0).addr.getAddress == InetAddress.getLoopbackAddress)
    assert(routers(0).servers(0).addr.getPort == 1)

    assert(routers(1).label == "fancy")
    assert(routers(1).protocol == TestProtocol.Fancy)
    assert(routers(1).params[TestProtocol.FancyParam].pants == false)
    assert(routers(1).servers.size == 1)
    assert(routers(1).servers(0).addr.getAddress == InetAddress.getLoopbackAddress)
    assert(routers(1).servers(0).addr.getPort == 2)
  }

  test("empty object") {
    val e = intercept[com.fasterxml.jackson.databind.JsonMappingException] { parse("") }
  }

  test("list instead of an object") {
    val yaml =
      """|- foo
         |- bar
         |""".stripMargin
    val ut = intercept[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("invalid routers") {
    val yaml =
      """|routers:
         |  protocol: foo
         |""".stripMargin
    val ut = intercept[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("protocol-specific params not supported in global context") {
    val yaml =
      """|fancyRouter: true
         |routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |- protocol: fancy
         |  experimental: true
         |  servers:
         |  - port: 2
         |""".stripMargin
    assertThrows[com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException] { parse(yaml) }
  }

  test("router labels conflict") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |- protocol: plain
         |  servers:
         |  - port: 2
         |""".stripMargin
    assertThrows[ConflictingLabels] { parse(yaml) }
  }

  test("router labels don't conflict") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |- protocol: plain
         |  label: yohourt
         |  servers:
         |  - port: 2
         |""".stripMargin
    assert(parse(yaml).routers.map(_.label) == Seq("plain", "yohourt"))
  }

  test("servers conflict across routers") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  label: router1
         |  servers:
         |  - port: 2
         |- protocol: plain
         |  label: router2
         |  servers:
         |  - port: 1
         |  - port: 2
         |  - port: 3
         |""".stripMargin
    assertThrows[ConflictingPorts] { parse(yaml) }
  }

  test("servers conflict within a router") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1234
         |  - port: 1234
         |""".stripMargin
    assertThrows[ConflictingPorts] { parse(yaml) }
  }

  test("servers don't conflict on different ips") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - ip: 127.0.0.2
         |    port: 3
         |- protocol: fancy
         |  experimental: true
         |  servers:
         |  - port: 3
         |""".stripMargin
    assert(parse(yaml).routers.flatMap(_.servers.map(_.addr)) == Seq(
      new InetSocketAddress("127.2", 3),
      new InetSocketAddress(InetAddress.getLoopbackAddress, 3)
    ))
  }

  test("with namers") {
    val yaml =
      """|namers:
         |- kind: test
         |  prefix: /n
         |  buh: true
         |routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |""".stripMargin
    val linker = parse(yaml)
    val Namers(namers) = linker.routers.head.params[Namers]
    assert(namers != Nil)
    assert(linker.namers != Nil)
  }

  test("with namers & telemetry") {
    val yaml =
      """|telemetry:
         |- kind: io.l5d.testTelemeter
         |  metrics: true
         |- kind: io.l5d.testTelemeter
         |  tracing: true
         |- kind: io.l5d.testTelemeter
         |  metrics: true
         |  tracing: true
         |namers:
         |- kind: test
         |routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |""".stripMargin
    val linker = parse(yaml)
    linker.routers.head.params[Namers].namers match {
      case Seq((_, namer)) => assert(namer.isInstanceOf[TestNamer])
      case namers => fail(s"unexpected namers: $namers")
    }
    linker.namers match {
      case Seq((_, namer: TestNamer)) =>
      case namers => fail(s"unexpected namers: $namers")
    }
    assert(linker.telemeters.size == 5)

    val ttracers = linker.telemeters.map(_.tracer).collect { case t: BufferingTracer => t }
    assert(ttracers.size == 2)
    val treceivers = linker.telemeters.map(_.stats).collect { case s: InMemoryStatsReceiver => s }
    assert(treceivers.size == 2)

    val param.Stats(stats) = linker.routers.head.params[param.Stats]
    stats.counter("rad").incr()
    treceivers.foreach { r =>
      assert(r.counters == Map(Seq("rt", "plain", "rad") -> 1))
    }

    val param.Tracer(tracer) = linker.routers.head.params[param.Tracer]
    Trace.letTracer(tracer) {
      Trace.recordBinary("ugh", "hi")
    }
    ttracers.foreach { t =>
      assert(t.size == 1)
    }
  }

  test("with admin") {
    val yaml =
      """|admin:
         |  ip: 127.0.0.1
         |  port: 42000
         |routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |""".stripMargin
    val linker = parse(yaml)
    assert(linker.admin.address.asInstanceOf[InetSocketAddress].getHostString == "127.0.0.1")
    assert(linker.admin.address.asInstanceOf[InetSocketAddress].getPort == 42000)
  }

  test("with admin default") {
    val yaml =
      """|routers:
        |- protocol: plain
        |  servers:
        |  - port: 1
        |""".stripMargin
    val linker = parse(yaml)
    assert(linker.admin.address.asInstanceOf[InetSocketAddress].getHostString == "0.0.0.0")
    assert(linker.admin.address.asInstanceOf[InetSocketAddress].getPort == 9990)
  }

  test("conflicting subtypes") {
    val yaml =
      """|namers:
         |- kind: test
         |routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |""".stripMargin
    assertThrows[ConflictingSubtypes] {
      initializer(namers = Seq(TestNamerInitializer, ConflictingNamerInitializer)).load(yaml)
    }
  }

  test("experimental required") {
    val yaml =
      """|routers:
         |- protocol: fancy
         |  servers:
         |  - port: 1
         |""".stripMargin
    val iae = intercept[IllegalArgumentException] {
      parse(yaml)
    }
    assert(iae.getMessage ==
      "The fancy protocol is experimental and must be explicitly enabled by " +
      "setting the `experimental' parameter to `true' on each router.")
  }
}
