package io.buoyant.namer.dnssrv

import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.{Addr, Name, NameTree, Path}
import com.twitter.util.{Duration, NullTimer}
import io.buoyant.namer.RichActivity
import io.buoyant.test.{Awaits, FunSuite}
import org.scalatest.Matchers
import org.xbill.DNS

class DnsSrvNamerIntegrationTest extends FunSuite with Awaits with Matchers {
  test("can resolve some public SRV revord") {
    val namer = new DnsSrvNamer(Path.empty, new DNS.ExtendedResolver, new NullTimer, Duration.Zero, new NullStatsReceiver)
    await(namer.lookup(Path.read("/_http._tcp.mxtoolbox.com.")).toFuture) match {
      case NameTree.Leaf(Name.Bound(varAddr)) => varAddr.sample() match {
        case Addr.Bound(addrs, _) => addrs should not be empty
        case addr => fail(s"unexpected addr: $addr")
      }
      case other => fail(s"unexpected result: $other")
    }
  }
}