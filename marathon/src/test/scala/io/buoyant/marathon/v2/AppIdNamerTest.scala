package io.buoyant.marathon.v2

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.util.{Activity, Future, Promise}
import io.buoyant.test.Awaits
import java.net.{InetSocketAddress, SocketAddress}
import org.scalatest.FunSuite

class AppIdNamerTest extends FunSuite with Awaits {

  test("Namer stays pending while looking up appId for the first time") {
    class TestApi() extends Api {
      def getAppIds(): Future[Api.AppIds] = Future.never
      def getAddrs(app: String): Future[Set[Address]] = Future.never
    }

    val namer = new AppIdNamer(new TestApi(), Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assert(state == Activity.Pending)
  }

  test("Namer fails if the marathon api cannot be reached") {
    class TestApi() extends Api {
      def getAppIds(): Future[Api.AppIds] = Future.exception(ChannelWriteException(null))
      def getAddrs(app: String): Future[Set[Address]] = Future.never
    }

    val namer = new AppIdNamer(new TestApi(), Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assert(state == Activity.Failed(ChannelWriteException(null)))
  }

  test("Namer returns neg when appId does not exist") {
    class TestApi() extends Api {
      def getAppIds(): Future[Api.AppIds] = Future.value(Set[String]("/foo", "/bar"))
      def getAddrs(app: String): Future[Set[Address]] = Future.never
    }

    val namer = new AppIdNamer(new TestApi(), Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/nosuchservice/residual")).states.respond(state = _)

    assert(state == Activity.Ok(NameTree.Neg))
  }

  test("Namer handles looking up /app/id") {
    class TestApi() extends Api {
      def getAppIds(): Future[Api.AppIds] = {
        Future.value(Set("/service/name"))
      }
      def getAddrs(app: String): Future[Set[Address]] = Future.never
    }

    val namer = new AppIdNamer(new TestApi(), Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    val input = Path.Utf8("service", "name", "residual")
    val output = Path.Utf8("io.l5d.marathon", "service", "name")
    namer.lookup(input).states.respond(state = _)
    assert(state == Activity.Ok(NameTree.Leaf(output)))
  }

  test("Namer updates when blocking call from getAppIds returns") {
    val blockingCallResponder = new Promise[Unit]

    class TestApi() extends Api {
      def getAppIds(): Future[Api.AppIds] = {
        blockingCallResponder before Future.value(Set[String]("/foo", "/servicename"))
      }
      def getAddrs(app: String): Future[Set[Address]] = Future.never
    }

    val namer = new AppIdNamer(new TestApi(), Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assert(state == Activity.Pending)
    blockingCallResponder.setDone()
    assert(state == Activity.Ok(NameTree.Leaf(Path.Utf8("io.l5d.marathon", "servicename"))))
  }

  def assertOnAddrs(state: Activity.State[NameTree[Name]])(f: Set[Address] => Unit) = state match {
    case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
      bound.addr.sample() match {
        case Addr.Bound(addrs, metadata) => f(addrs)
        case _ => assert(false)
      }
    case _ => assert(false)
  }

  test("Namer returns leaf with bound addr when addr exist") {
    class TestApi() extends Api {
      def getAppIds(): Future[Api.AppIds] = Future.value(Set[String]("/foo", "/servicename"))
      def getAddrs(app: String): Future[Set[Address]] = Future.value(
        Set[Address](Address("hostname", 8080))
      )
    }

    val namer = new AppIdNamer(new TestApi(), Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    assertOnAddrs(state) { addrs =>
      assert(addrs.size == 1)
      assert(addrs.head.toString.contains("hostname:8080"))
    }
  }

  test("Addrs update when blocking call for getAddrs returns") {
    val blockingCallResponder = new Promise[Unit]

    class TestApi() extends Api {
      def getAppIds(): Future[Api.AppIds] = Future.value(Set[String]("/foo", "/servicename"))
      def getAddrs(app: String): Future[Set[Address]] = {
        blockingCallResponder before
          Future.value(Set[Address](Address("hostname", 8080)))
      }
    }

    val namer = new AppIdNamer(new TestApi(), Path.Utf8("io.l5d.marathon"), 250.millis)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/servicename/residual")).states.respond(state = _)

    state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) => assert(bound.addr.sample() == Addr.Pending)
      case _ => assert(false)
    }
    blockingCallResponder.setDone()
    assertOnAddrs(state) { addrs =>
      assert(addrs.size == 1)
      assert(addrs.head.toString.contains("hostname:8080"))
    }
  }
}
