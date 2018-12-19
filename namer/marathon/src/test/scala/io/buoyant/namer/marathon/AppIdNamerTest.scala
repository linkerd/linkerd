package io.buoyant.namer.marathon

import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.marathon.v2.{Api, WatchState}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class AppIdNamerTest extends FunSuite with Awaits {
  val ttl = 10.millis
  val err = ChannelWriteException(None)
  val exc = Future.exception(err)
  def newTimer = new MockTimer

  case class TestApi(
    ids: Future[Api.AppIds] = Future.never,
    addrs: Future[Set[Address]] = Future.never,
    initIdsAlive: Boolean = true,
    initAddrsAlive: Boolean = true
  ) extends Api {

    @volatile var idsAlive = initIdsAlive
    @volatile var addrsAlive = initAddrsAlive

    def getAppIds(): Future[Api.AppIds] =
      if (idsAlive) ids else exc
    def getAddrs(app: Path, watchState: Option[WatchState] = None): Future[Set[Address]] =
      if (addrsAlive) addrs else exc
  }

  test("Namer stays pending while looking up appId for the first time") {
    val namer = new AppIdNamer(TestApi(), Path.Utf8("io.l5d.marathon"), ttl)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)
    assert(state == Activity.Pending)
  }

  test("Namer fails if the marathon api cannot be reached") {
    val api = TestApi(ids = exc)
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assert(state == Activity.Failed(err))
  }

  test("Namer returns neg when appId does not exist") {
    val api = TestApi(ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("bar"))))
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/nosuchservice/residual")).states.respond(state = _)

    assert(state == Activity.Ok(NameTree.Neg))
  }

  test("Namer returns neg when appId exists but there are no bound addresses") {
    val api = TestApi(
      ids = Future.value(Set(Path.read("/service/name"))),
      addrs = Future.value(Set.empty)
    )
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)

    val input = Path.Utf8("service", "name", "residual")
    val output = Path.Utf8("io.l5d.marathon", "service", "name")
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(input).states.respond(state = _)

    assert(state == Activity.Ok(NameTree.Neg))
  }

  test("Namer handles looking up /app/id") {
    val addrs = Set(Address("hostname", 8080))
    val api = TestApi(
      ids = Future.value(Set(Path.read("/service/name"))),
      addrs = Future.value(addrs)
    )
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)

    val input = Path.Utf8("service", "name", "residual")
    val output = Path.Utf8("io.l5d.marathon", "service", "name")
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(input).states.respond(state = _)

    assert(state == Activity.Ok(NameTree.Leaf(output)))
  }

  test("Namer handles looking up /app/id case-insensitive") {
    val addrs = Set(Address("hostname", 8080))
    val api = TestApi(
      ids = Future.value(Set(Path.read("/service/name"))),
      addrs = Future.value(addrs)
    )
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)

    val input = Path.Utf8("service", "Name", "residual")
    val output = Path.Utf8("io.l5d.marathon", "service", "name")
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(input).states.respond(state = _)

    assert(state == Activity.Ok(NameTree.Leaf(output)))
  }

  test("Namer updates when blocking call from getAppIds returns") {
    val promisedAppIds = new Promise[Api.AppIds]
    val addrs = Set(Address("hostname", 8080))
    val api = TestApi(ids = promisedAppIds, addrs = Future.value(addrs))
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assert(state == Activity.Pending)
    promisedAppIds.setValue(Set(Path.Utf8("foo"), Path.Utf8("servicename")))
    assert(state == Activity.Ok(NameTree.Leaf(Path.Utf8("io.l5d.marathon", "servicename"))))
  }

  def assertAddrs(state: Activity.State[NameTree[Name]], expected: Set[Address]) = state match {
    case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
      bound.addr.sample() match {
        case Addr.Bound(addrs, metadata) => assert(addrs == expected)
        case addr => fail(s"$addr is not bound")
      }
    case state => fail(s"$state is not a NameTree.Leaf[Name]")
  }

  def assertActivityFail(state: Activity.State[NameTree[Name]]) = state match {
    case Activity.Failed(reason) => assert(reason == err)
    case state => fail(s"$state is not Activity.Failed()")
  }

  test("Namer returns leaf with bound addr when addr exist") {
    val addrs = Set(Address("hostname", 8080))
    val api = TestApi(
      ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("servicename"))),
      addrs = Future.value(addrs)
    )

    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assertAddrs(state, addrs)
  }

  test("Addrs update when blocking call for getAddrs returns") {
    val promisedAddrs = new Promise[Set[Address]]
    val api = TestApi(
      ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("servicename"))),
      addrs = promisedAddrs
    )
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)
    assert(state == Activity.Pending)

    val addresses = Set(Address("hostname", 8080))
    promisedAddrs.setValue(addresses)
    assertAddrs(state, addresses)
  }

  test("Namer stays pending before blocking call for getAddrs returns") {
    val promisedAddrs = new Promise[Set[Address]]
    val api = TestApi(
      ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("servicename"))),
      addrs = promisedAddrs
    )
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)
    assert(state == Activity.Pending)
  }

  test("Namer recovers if marathon api fails initially") {
    val addrs = Set(Address("hostname", 8080))
    val api = TestApi(
      ids = Future.value(Set(Path.read("/foo/bar"))),
      addrs = Future.value(addrs),
      initIdsAlive = false,
      initAddrsAlive = true
    )

    val timer = newTimer
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl, timer)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    val input = Path.Utf8("foo", "bar", "residual")
    val output = Path.Utf8("io.l5d.marathon", "foo", "bar")

    Time.withCurrentTimeFrozen { tc =>
      namer.lookup(input).states.respond(state = _)
      assert(state == Activity.Failed(err))

      api.idsAlive = true
      tc.advance(ttl)
      timer.tick()

      namer.lookup(input).states.respond(state = _)
      assert(state == Activity.Ok(NameTree.Leaf(output)))
    }
  }

  test("Namer returns a cached address when the marathon api goes down") {
    val addrs = Set(Address("hostname", 8080))
    val api = TestApi(
      ids = Future.value(Set(Path.read("/foo/bar"))),
      addrs = Future.value(addrs),
      initIdsAlive = true,
      initAddrsAlive = true
    )

    val timer = newTimer
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl, timer)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    val input = Path.Utf8("foo", "bar", "residual")
    val output = Path.Utf8("io.l5d.marathon", "foo", "bar")

    Time.withCurrentTimeFrozen { tc =>
      namer.lookup(input).states.respond(state = _)
      assert(state == Activity.Ok(NameTree.Leaf(output)))

      api.idsAlive = false
      tc.advance(ttl)
      timer.tick()

      namer.lookup(input).states.respond(state = _)
      assert(state == Activity.Ok(NameTree.Leaf(output)))
    }
  }

  test("Namer returns failure if getAddrs() fails due to marathon api being down") {
    val api = TestApi(
      ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("bar"))),
      addrs = exc,
      initIdsAlive = true,
      initAddrsAlive = false
    )

    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/bar/residual")).states.respond(state = _)

    assertActivityFail(state)
  }

  test("Namer recovers if getAddrs() fails initially") {
    val addrs = Set(Address("hostname", 8080))

    val api = TestApi(
      ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("bar"))),
      addrs = Future.value(addrs),
      initIdsAlive = true,
      initAddrsAlive = false
    )

    val timer = newTimer
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl, timer)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    Time.withCurrentTimeFrozen { tc =>
      namer.lookup(Path.read("/bar/residual")).states.respond(state = _)

      assertActivityFail(state)

      api.addrsAlive = true
      tc.advance(ttl)
      timer.tick()

      namer.lookup(Path.read("/bar/residual")).states.respond(state = _)
      assertAddrs(state, addrs)
    }
  }

  test("Namer returns cached bound address if getAddrs() fails after a successful call") {
    val addr = Address("hostname", 8080)

    val api = TestApi(
      ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("bar"))),
      addrs = Future.value(Set(addr)),
      initIdsAlive = true,
      initAddrsAlive = true
    )

    val timer = newTimer
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), ttl, timer)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    Time.withCurrentTimeFrozen { tc =>
      namer.lookup(Path.read("/bar/residual")).states.respond(state = _)
      assert(state == Activity.Ok(NameTree.Leaf(Path.Utf8("io.l5d.marathon", "bar"))))

      @volatile var boundAddr: Var[Addr] = Var.value(Addr.Pending)
      state match {
        case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
          boundAddr = bound.addr
        case state => fail(s"$state is not a NameTree.Leaf[Name]")
      }

      @volatile var addrTest: Addr = Addr.Pending
      val closable = boundAddr.changes.respond(addrTest = _)

      try {
        assert(addrTest == Addr.Bound(addr))
        assertAddrs(state, Set(addr))

        api.addrsAlive = false
        tc.advance(ttl)
        timer.tick()

        namer.lookup(Path.read("/bar/residual")).states.respond(state = _)
        assert(addrTest == Addr.Bound(addr))
        assertAddrs(state, Set(addr))
      } finally {
        await(closable.close())
      }
    }
  }
}
