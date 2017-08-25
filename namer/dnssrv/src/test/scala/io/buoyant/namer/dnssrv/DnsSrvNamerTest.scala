package io.buoyant.namer.dnssrv

import com.twitter.finagle._
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.util.LoadService
import com.twitter.util.{Duration, NullTimer}
import io.buoyant.config.Parser
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.test.FunSuite
import org.scalatest.Matchers
import org.xbill.DNS

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
    val curator = mapper.readValue[NamerConfig](yaml).asInstanceOf[DnsSrvNamerConfig]
    assert(curator.refreshIntervalSeconds === Some(60))
    assert(curator.dnsHosts === Some(Seq("localhost")))
  }

  test("can resolve some public SRV revord") {
    val namer = new DnsSrvNamer(Path.empty, new DNS.ExtendedResolver, new NullTimer, Duration.Zero, new NullStatsReceiver)
    val result = namer.lookupSrv("_http._tcp.mxtoolbox.com.", Path.read("/foo"), null)
    result.get().simplified match {
      case NameTree.Leaf(Name.Bound(varAddr)) => varAddr.sample() match {
        case Addr.Bound(addrs, _) => addrs should not be empty
        case a => fail(s"unexpected addr: $a")
      }
      case x => fail(s"unexpected result: $x")
    }
  }
}
