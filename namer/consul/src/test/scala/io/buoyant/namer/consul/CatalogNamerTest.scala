package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Promise}
import io.buoyant.consul.v1._
import io.buoyant.namer.Metadata
import io.buoyant.test.Awaits
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

  def assertOnAddrs(
    state: Activity.State[NameTree[Name]]
  )(f: (Set[Address], Addr.Metadata) => Unit) = state match {
    case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
      bound.addr.sample() match {
        case Addr.Bound(addrs, metadata) => f(addrs, metadata)
        case _ => assert(false)
      }
    case _ => assert(false)
  }

  class TestAgentApi(domain: String) extends AgentApi(null, "/v1") {
    override def localAgent(retry: Boolean): Future[LocalAgent] =
      Future.value(LocalAgent(Config = Some(Config(Domain = Some(domain)))))
  }

  test("Namer stays pending while looking up datacenter for the first time") {
    class TestCatalogApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = Future.never
    }
    val namer = new CatalogNamer(
      testPath,
      new TestCatalogApi(),
      new TestAgentApi("acme.co"),
      includeTag = false,
      setHost = false
    )
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
    val namer = new CatalogNamer(testPath, new TestApi(), new TestAgentApi("acme.co"), false, false)
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
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.exception(new UnexpectedResponse(null))
        case _ => Future.never //don't respond to blocking index calls
      }
    }
    val namer = new CatalogNamer(testPath, new TestApi(), new TestAgentApi("acme.co"), false, false)
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
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed(Seq.empty, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }
    }
    val namer = new CatalogNamer(testPath, new TestApi(), new TestAgentApi("acme.co"), false, false)
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
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed(Seq.empty, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }
    }
    val namer = new CatalogNamer(testPath, new TestApi(), new TestAgentApi("acme.co"), false, false)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Ok(NameTree.Neg))
    blockingCallResponder.setDone()
    assert(state == Activity.Ok(
      NameTree.Leaf(Path(Buf.Utf8("test"), Buf.Utf8("dc1"), Buf.Utf8("servicename")))
    ))
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
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None =>
          Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case _ => Future.never // don't respond to blocking index calls
      }
    }

    val namer = new CatalogNamer(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("acme.co"),
      includeTag = false,
      setHost = false
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (addrs, _) =>
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
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None =>
          Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case Some("1") =>
          blockingCallResponder before Future.value(Indexed[Seq[ServiceNode]](Seq.empty, Some("2")))
        case _ => Future.never
      }
    }

    val namer = new CatalogNamer(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("acme.co"),
      includeTag = false,
      setHost = false
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (addrs, _) => assert(addrs.size == 1) }

    blockingCallResponder.setDone()

    state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) => assert(bound.addr.sample() == Addr.Neg)
      case _ => assert(false)
    }
  }

  test("Namer filters by tag") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None =>
          val rsp = Map("consul" -> Seq(), "servicename" -> Seq("master", "staging"))
          Future.value(Indexed(rsp, Some("1")))
        case _ => Future.never // don't respond to blocking index calls
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None =>
          tag match {
            case Some("master") =>
              Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
            case _ => Future.value(Indexed(Nil, Some("1")))
          }
        case _ => Future.never // don't respond to blocking index calls
      }
    }

    val namer = new CatalogNamer(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("acme.co"),
      includeTag = true,
      setHost = false
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/master/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (addrs, _) =>
      assert(addrs.size == 1)
      assert(addrs.head.toString.contains("192.168.1.35:8080"))
    }
  }

  test("Namer returns authority in bound address metadata when setHost is true") {
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
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None =>
          tag match {
            case Some("master") =>
              Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
            case _ => Future.value(Indexed(Nil, Some("1")))
          }
        case _ => Future.never //don't respond to blocking index calls
      }
    }

    val namer = new CatalogNamer(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("consul.acme.co"),
      includeTag = true,
      setHost = true
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/master/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (_, metadata) =>
      assert(metadata == Addr.Metadata(Metadata.authority -> "servicename.service.dc1.consul.acme.co"))
    }
  }
}
