package io.buoyant.namerd.iface

import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.logging.Level
import com.twitter.util._
import io.buoyant.namer.{ConfiguredDtabNamer, DelegateTree, Metadata, RichActivity}
import io.buoyant.namerd.NullDtabStore
import io.buoyant.test.{Awaits, FunSuite}
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.time._

class HttpNamerEndToEndTest extends FunSuite with Eventually with IntegrationPatience with Awaits {

  implicit override val patienceConfig = PatienceConfig(
    timeout = scaled(Span(5, Seconds)),
    interval = scaled(Span(100, Milliseconds))
  )

  def retryIn() = 1.second
  val clientId = Path.empty
  val ns = "testns"

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
    val service = new HttpControlService(NullDtabStore, interpreter, namers)
    val client = new StreamingNamerClient(service, ns)

    val act = client.bind(reqDtab, reqPath)
    val obs = act.states.respond { s =>
      clientState = s
    }
    assert(clientState == Activity.Pending)

    val serverAddr0 = Var[Addr](Addr.Bound())
    serverState() = Activity.Ok(NameTree.Leaf(Name.Bound(serverAddr0, id)))
    eventually { assert(clientState == serverState.sample()) }
    val Activity.Ok(NameTree.Leaf(bound0)) = clientState
    assert(bound0.id == id)

    @volatile var clientAddr0: Addr = Addr.Pending
    bound0.addr.changes.respond(clientAddr0 = _)
    assert(clientAddr0 == Addr.Bound())

    serverAddr0() = Addr.Bound(
      Set(Address("127.1", 4321)),
      Addr.Metadata(Metadata.authority -> "acme.co")
    )
    eventually {
      assert(clientAddr0 == Addr.Bound(Set(Address("127.1", 4321)), Addr.Metadata(Metadata.authority -> "acme.co")))
    }

    serverAddr0() = Addr.Bound(
      Set(Address("127.1", 5432)),
      Addr.Metadata(Metadata.authority -> "acme.co")
    )
    eventually {
      assert(clientAddr0 == Addr.Bound(Set(Address("127.1", 5432)), Addr.Metadata(Metadata.authority -> "acme.co")))
    }

    serverState() = Activity.Ok(NameTree.Neg)
    eventually { assert(clientState == serverState.sample()) }

    eventually { assert(clientAddr0 == Addr.Neg) }

    val serverAddr1 = Var[Addr](Addr.Bound())
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

  test("delegation") {
    val id = Path.read("/io.l5d.w00t")
    val namer = new Namer {
      def lookup(path: Path) = {
        path match {
          case Path.Utf8("woop") => Activity.value(NameTree.Leaf(Name.Bound(
            Var(
              Addr.Bound(
                Set(Address("localhost", 9000)),
                Addr.Metadata(Metadata.authority -> "acme.co")
              )
            ),
            Path.read("/io.l5d.w00t/woop"),
            Path.empty
          )))
          case _ => Activity.value(NameTree.Neg)
        }
      }
    }
    val namers = Seq(id -> namer)
    def interpreter(ns: String) = new ConfiguredDtabNamer(
      Activity.value(Dtab.read("/srv => /io.l5d.w00t; /host => /srv; /svc => /host")),
      namers
    )
    val service = new HttpControlService(NullDtabStore, interpreter, namers.toMap)
    val client = new StreamingNamerClient(service, ns)

    val tree = await(client.delegate(
      Dtab.read("/host/poop => /srv/woop"),
      Path.read("/svc/poop")
    ))

    assert(tree ==
      DelegateTree.Delegate(
        Path.read("/svc/poop"),
        Dentry.nop,
        DelegateTree.Alt(
          Path.read("/host/poop"),
          Dentry.read("/svc=>/host"),
          List(
            DelegateTree.Delegate(
              Path.read("/srv/woop"),
              Dentry.read("/host/poop=>/srv/woop"),
              DelegateTree.Leaf(
                Path.read("/io.l5d.w00t/woop"),
                Dentry.read("/srv=>/io.l5d.w00t"),
                Path.read("/io.l5d.w00t/woop")
              )
            ),
            DelegateTree.Delegate(
              Path.read("/srv/poop"),
              Dentry.read("/host=>/srv"),
              DelegateTree.Neg(
                Path.read("/io.l5d.w00t/poop"),
                Dentry.read("/srv=>/io.l5d.w00t")
              )
            )
          ): _*
        )
      ))
  }

  test("use last good bind data") {
    val id = Path.read("/io.l5d.w00t")
    val (act, witness) = Activity[NameTree[Name]]()
    val namer = new Namer {
      def lookup(path: Path) = act
    }
    val namers = Seq(id -> namer)
    def interpreter(ns: String) = new ConfiguredDtabNamer(
      Activity.value(Dtab.read("/svc => /io.l5d.w00t")),
      namers
    )
    val service = new HttpControlService(NullDtabStore, interpreter, namers.toMap)
    val client = new StreamingNamerClient(service, ns)
    witness.notify(Return(NameTree.Leaf(Name.Bound(
      Var(Addr.Bound(Address("localhost", 9000))),
      Path.read("/io.l5d.w00t/foo"),
      Path.empty
    ))))

    val bindAct = client.bind(Dtab.empty, Path.read("/svc/foo"))
    var bound: NameTree[Name.Bound] = null
    // hold activity open so that it doesn't get restarted and lose state
    val bindObs = bindAct.values.respond(_ => ())
    try {
      val NameTree.Leaf(bound0) = await(bindAct.toFuture)
      // hold var open so that it doesn't get restarted and lose state
      val bound0Obs = bound0.addr.changes.respond(_ => ())
      try {
        assert(bound0.id == Path.read("/io.l5d.w00t/foo"))
        assert(bound0.addr.sample == Addr.Bound(Address("localhost", 9000)))

        witness.notify(Throw(new Exception("bind failure")))
        val NameTree.Leaf(bound1) = await(bindAct.toFuture)
        assert(bound1.id == Path.read("/io.l5d.w00t/foo"))
        assert(bound1.addr.sample == Addr.Bound(Address("localhost", 9000)))
      } finally await(bound0Obs.close())
    } finally await(bindObs.close())

  }
}
