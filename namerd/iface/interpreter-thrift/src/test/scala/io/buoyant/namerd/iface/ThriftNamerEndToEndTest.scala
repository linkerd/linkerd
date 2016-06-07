package io.buoyant.namerd.iface

import com.twitter.conversions.time._
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.{Addr, Address, Dtab, Name, Namer, NameTree, Path}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util._
import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.FunSuite
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.time._

class ThriftNamerEndToEndTest extends FunSuite with Eventually with IntegrationPatience {
  import ThriftNamerInterface._

  implicit override val patienceConfig = PatienceConfig(
    timeout = scaled(Span(5, Seconds)),
    interval = scaled(Span(100, Milliseconds))
  )

  def retryIn() = 1.second
  val clientId = Path.empty
  val ns = "testns"

  def newStamper = {
    val stampCounter = new AtomicLong(1)
    () => Stamp.mk(stampCounter.getAndIncrement)
  }

  test("service resurrection") {
    val serverState = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    @volatile var clientState: Activity.State[NameTree[Name.Bound]] = Activity.Pending

    val reqDtab = Dtab.read("/woop => /w00t")
    val reqPath = Path.read("/woop/woop")
    val id = Path.read("/io.l5d.w00t/woop")
    val namer = new Namer {
      def lookup(path: Path) = path match {
        case Path.Utf8("woop") => Activity(serverState)
        case _ => Activity.exception(new Exception)
      }
    }
    def interpreter(ns: String) = new NameInterpreter {
      def bind(dtab: Dtab, path: Path) =
        if (dtab == reqDtab && path == reqPath) Activity(serverState)
        else Activity.exception(new Exception)
    }
    val namers = Map(Path.read("/io.l5d.w00t") -> namer)
    val service = new ThriftNamerInterface(interpreter, namers, newStamper, retryIn, Capacity.default, NullStatsReceiver)
    val client = new ThriftNamerClient(service, ns, clientId)

    val act = client.bind(reqDtab, reqPath)
    val obs = act.states.respond { s =>
      clientState = s
    }
    assert(clientState == Activity.Pending)

    val serverAddr0 = Var[Addr](Addr.Pending)
    serverState() = Activity.Ok(NameTree.Leaf(Name.Bound(serverAddr0, id)))
    eventually { assert(clientState == serverState.sample()) }
    val Activity.Ok(NameTree.Leaf(bound0)) = clientState
    assert(bound0.id == id)

    @volatile var clientAddr0: Addr = Addr.Pending
    bound0.addr.changes.respond(clientAddr0 = _)
    assert(clientAddr0 == Addr.Pending)

    serverAddr0() = Addr.Bound(Address("127.1", 4321))
    eventually { assert(clientAddr0 == serverAddr0.sample()) }

    serverAddr0() = Addr.Bound(Address("127.1", 5432))
    eventually { assert(clientAddr0 == serverAddr0.sample()) }

    serverState() = Activity.Ok(NameTree.Neg)
    eventually { assert(clientState == serverState.sample()) }

    eventually { assert(clientAddr0 == Addr.Neg) }

    val serverAddr1 = Var[Addr](Addr.Pending)
    serverState() = Activity.Ok(NameTree.Leaf(Name.Bound(serverAddr1, id)))
    eventually { assert(clientState == serverState.sample()) }
    val Activity.Ok(NameTree.Leaf(bound1)) = clientState
    assert(bound1.id == id)

    @volatile var clientAddr1: Addr = Addr.Pending
    bound1.addr.changes.respond(clientAddr1 = _)

    serverAddr1() = Addr.Bound(Address("127.1", 5432))
    eventually { assert(clientAddr1 == serverAddr1.sample()) }

    serverAddr1() = Addr.Bound(Address("127.1", 6543))
    eventually { assert(clientAddr1 == serverAddr1.sample()) }
  }
}
