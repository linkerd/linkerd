package io.buoyant.marathon.v2

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.util.{Activity, Future, Promise}
import io.buoyant.test.Awaits
import java.net.{InetSocketAddress, SocketAddress}
import org.scalatest.FunSuite

class AppIdNamerTest extends FunSuite with Awaits {
  case class TestApi(
    ids: Future[Api.AppIds] = Future.never,
    addrs: Future[Set[Address]] = Future.never
  ) extends Api {
    def getAppIds(): Future[Api.AppIds] = ids
    def getAddrs(app: Path): Future[Set[Address]] = addrs
  }

  test("Namer stays pending while looking up appId for the first time") {
    val namer = new AppIdNamer(TestApi(), Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)
    assert(state == Activity.Pending)
  }

  test("Namer fails if the marathon api cannot be reached") {
    val err = ChannelWriteException(null)
    val api = TestApi(ids = Future.exception(err))
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), 250.millis)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assert(state == Activity.Failed(err))
  }

  test("Namer returns neg when appId does not exist") {
    val api = TestApi(ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("bar"))))
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), 250.millis)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/nosuchservice/residual")).states.respond(state = _)

    assert(state == Activity.Ok(NameTree.Neg))
  }

  test("Namer handles looking up /app/id") {
    val api = TestApi(ids = Future.value(Set(Path.read("/service/name"))))
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), 250.millis)

    val input = Path.Utf8("service", "name", "residual")
    val output = Path.Utf8("io.l5d.marathon", "service", "name")
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(input).states.respond(state = _)

    assert(state == Activity.Ok(NameTree.Leaf(output)))
  }

  test("Namer updates when blocking call from getAppIds returns") {
    val promisedAppIds = new Promise[Api.AppIds]
    val api = TestApi(ids = promisedAppIds)
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), 250.millis)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assert(state == Activity.Pending)
    promisedAppIds.setValue(Set(Path.Utf8("foo"), Path.Utf8("servicename")))
    assert(state == Activity.Ok(NameTree.Leaf(Path.Utf8("io.l5d.marathon", "servicename"))))
  }

  def assertOnAddrs(state: Activity.State[NameTree[Name]])(f: Set[Address] => Unit) = state match {
    case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
      bound.addr.sample() match {
        case Addr.Bound(addrs, metadata) => f(addrs)
        case addr => fail(s"$addr is not bound")
      }
    case state => fail(s"$state is not a NameTree.Leaf[Name]")
  }

  test("Namer returns leaf with bound addr when addr exist") {
    val api = TestApi(
      ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("servicename"))),
      addrs = Future.value(Set(Address("hostname", 8080)))
    )

    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assertOnAddrs(state) { addrs =>
      assert(addrs.size == 1)
      assert(addrs.head.toString.contains("hostname:8080"))
    }
  }

  test("Addrs update when blocking call for getAddrs returns") {
    val promisedAddrs = new Promise[Set[Address]]
    val api = TestApi(
      ids = Future.value(Set(Path.Utf8("foo"), Path.Utf8("servicename"))),
      addrs = promisedAddrs
    )
    val namer = new AppIdNamer(api, Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) => assert(bound.addr.sample() == Addr.Pending)
      case state => fail(s"$state is not a NameTree.Leaf[Name.Bound]")
    }
    val addresses = Set(Address("hostname", 8080))
    promisedAddrs.setValue(addresses)
    assertOnAddrs(state) { addrs =>
      assert(addrs == addresses)
    }
  }
}
