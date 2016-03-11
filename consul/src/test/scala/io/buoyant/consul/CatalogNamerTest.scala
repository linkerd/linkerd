package io.buoyant.consul

import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Promise}
import io.buoyant.consul.v1.{CatalogApi, Indexed, ServiceNode}
import io.buoyant.test.Awaits
import java.net.SocketAddress
import org.scalatest.FunSuite

class CatalogNamerTest extends FunSuite with Awaits {

  val testPath = Path.read("/test")
  val testServiceNode = ServiceNode(
    Some("node"),
    Some("192.168.1.35"),
    Some("servicename"),
    Some("servicename"),
    Some(Seq.empty),
    Some(""),
    Some(8080)
  )

  def assertOnAddrs(state: Activity.State[NameTree[Name]])(f: Set[Address] => Unit) = state match {
    case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
      bound.addr.sample() match {
        case Addr.Bound(addrs, metadata) => f(addrs)
        case _ => assert(false)
      }
    case _ => assert(false)
  }

  test("Namer stays pending while looking up datacenter for the first time") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = Future.never
    }
    val namer = new CatalogNamer(testPath, _ => new TestApi())
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Pending)
  }

  test("Namer fails if the consul api cannot be reached") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = Future.exception(ChannelWriteException(null))
    }
    val namer = new CatalogNamer(testPath, _ => new TestApi())
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Failed(ChannelWriteException(null)))
  }

  test("Namer returns neg when dc does not exist") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None => Future.exception(new UnexpectedResponse(null))
        case _ => Future.never //don't respond to blocking index calls
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.exception(new UnexpectedResponse(null))
        case _ => Future.never //don't respond to blocking index calls
      }
    }
    val namer = new CatalogNamer(testPath, _ => new TestApi())
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/nosuchdc/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Ok(NameTree.Neg))
  }

  test("Namer returns neg when servicename does not exist in serviceMap response") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed(Map("consul" -> Seq.empty), Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed(Seq.empty, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }
    }
    val namer = new CatalogNamer(testPath, _ => new TestApi())
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/nosuchservice/residual")).states respond { state = _ }

    assert(state == Activity.Ok(NameTree.Neg))
  }

  test("Namer updates when serviceMap blocking calls return") {
    val blockingCallResponder = new Promise[Unit]

    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed(Map("consul" -> Seq.empty), Some("1")))
        case Some("1") =>
          val rsp = Map("consul" -> Seq(), "servicename" -> Seq("master", "staging"))
          blockingCallResponder before Future.value(Indexed(rsp, Some("2")))
        case _ => Future.never
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed(Seq.empty, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }
    }
    val namer = new CatalogNamer(testPath, _ => new TestApi())
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Ok(NameTree.Neg))
    blockingCallResponder.setDone()
    assert(state == Activity.Ok(NameTree.Leaf(Path(Buf.Utf8("test"), Buf.Utf8("dc1"), Buf.Utf8("servicename")))))
  }

  test("Namer returns leaf with bound addr when serviceNodes exist") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None =>
          val rsp = Map("consul" -> Seq(), "servicename" -> Seq("master", "staging"))
          Future.value(Indexed(rsp, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }
    }

    val namer = new CatalogNamer(Path.read("/test"), s => new TestApi())
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { addrs =>
      assert(addrs.size == 1)
      assert(addrs.head.toString.contains("192.168.1.35:8080"))
    }
  }

  test("Addrs update when blocking call for serviceNodes returns") {
    val blockingCallResponder = new Promise[Unit]

    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None =>
          val rsp = Map("consul" -> Seq(), "servicename" -> Seq("master", "staging"))
          Future.value(Indexed(rsp, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case Some("1") => blockingCallResponder before Future.value(Indexed[Seq[ServiceNode]](Seq.empty, Some("2")))
        case _ => Future.never
      }
    }

    val namer = new CatalogNamer(Path.read("/test"), s => new TestApi())
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { addrs => assert(addrs.size == 1) }

    blockingCallResponder.setDone()

    state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) => assert(bound.addr.sample() == Addr.Neg)
      case _ => assert(false)
    }
  }
}
