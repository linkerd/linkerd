package io.buoyant.namer.dnssrv

import com.twitter.finagle._
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.test.FunSuite
import org.scalatest.Matchers

class DnsSrvNamerTest extends FunSuite with Matchers {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = DnsSrvNamerConfig(
      Some(15),
      Some(List("localhost"))
    ).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[DnsSrvNamerInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.dnssrv
                  |experimental: true
                  |refreshIntervalSeconds: 60
                  |dnsHosts:
                  |- localhost
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(DnsSrvNamerInitializer)))
    val config = mapper.readValue[NamerConfig](yaml).asInstanceOf[DnsSrvNamerConfig]
    assert(config.refreshIntervalSeconds === Some(60))
    assert(config.dnsHosts === Some(Seq("localhost")))
    assert(config.disabled === false)
  }
}
