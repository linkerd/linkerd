package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Promise}
import io.buoyant.consul.v1._
import io.buoyant.namer.Metadata
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ConsulNamerTest extends FunSuite with Awaits {

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
        case Addr.Failed(e) => throw e
        case addr => fail(s"unexpected addr: $addr")
      }
    case Activity.Failed(e) => throw e
    case state => fail(s"unexpected state: $state")
  }

  class TestAgentApi(domain: String, datacenter: Option[String] = None) extends AgentApi(null, "/v1") {
    override def localAgent(retry: Boolean): Future[LocalAgent] =
      Future.value(LocalAgent(Config = Some(Config(Domain = Some(domain), Datacenter = datacenter))))
  }

  test("Namer stays pending while looking up datacenter for the first time") {
    class TestCatalogApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = Future.never
    }
    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(
      testPath,
      new TestCatalogApi(),
      new TestAgentApi("acme.co"),
      setHost = false,
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }
    assert(state == Activity.Pending)
    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer fails if the consul api cannot be reached") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = Future.exception(ChannelWriteException(null))
    }
    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(testPath, new TestApi(), new TestAgentApi("acme.co"), stats = stats)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Failed(ChannelWriteException(null)))
    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "errors") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer goes pending when dc does not exist") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = {
        // When the dc doesn't exist, consul throws a 500, which is
        // automatically retried indefinitely:
        Future.never
      }
    }
    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(testPath, new TestApi(), new TestAgentApi("acme.co"), stats = stats)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/nosuchdc/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Pending)
    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer returns neg when servicename does not exist in serviceMap response") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
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
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed(Seq.empty, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(testPath, new TestApi(), new TestAgentApi("acme.co"), stats = stats)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/nosuchservice/residual")).states respond { state = _ }

    assert(state == Activity.Ok(NameTree.Neg))
    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer updates when serviceMap blocking calls return") {
    val blockingCallResponder = new Promise[Unit]

    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
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
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None => Future.value(Indexed(Seq.empty, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(testPath, new TestApi(), new TestAgentApi("acme.co"), stats = stats)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Ok(NameTree.Neg))
    blockingCallResponder.setDone()
    assert(state == Activity.Ok(
      NameTree.Leaf(Path(Buf.Utf8("test"), Buf.Utf8("dc1"), Buf.Utf8("servicename")))
    ))
    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 2,
      Seq("dc", "adds") -> 4,
      Seq("lookups") -> 1
    ))
  }

  test("Namer returns leaf with bound addr when serviceNodes exist (case-insensitive)") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None =>
          val rsp = Map("consul" -> Seq(), "serviceNAME" -> Seq("master", "staging"))
          Future.value(Indexed(rsp, Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None =>
          Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case _ => Future.never // don't respond to blocking index calls
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("acme.co"),
      setHost = false,
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/SERVICEname/residual")).states respond { state = _ }

    assertOnAddrs(state) { (addrs, _) =>
      assert(addrs.size == 1)
      assert(addrs.head.toString.contains("192.168.1.35:8080"))
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("service", "closes") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer uses agent's datacenter when .local is used as datacenter name") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None =>
          val rsp = datacenter match {
            case Some("dc1") => Map("servicename" -> Seq("master", "staging"))
            case _ => Map.empty[String, Seq[String]]
          }
          Future.value(Indexed(rsp, Some("1")))
        case _ => Future.never // don't respond to blocking index calls
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None =>
          Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case _ => Future.never // don't respond to blocking index calls
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("acme.co", Some("dc1")),
      setHost = false,
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/.local/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (addrs, _) =>
      assert(addrs.size == 1)
      assert(addrs.head.toString.contains("192.168.1.35:8080"))
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 3,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("service", "closes") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Addrs update when blocking call for serviceNodes returns") {
    val blockingCallResponder = new Promise[Unit]

    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
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
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None =>
          Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case Some("1") =>
          blockingCallResponder before Future.value(Indexed[Seq[ServiceNode]](Seq.empty, Some("2")))
        case _ => Future.never
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("acme.co"),
      setHost = false,
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (addrs, _) => assert(addrs.size == 1) }

    blockingCallResponder.setDone()

    state match {
      case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) => assert(bound.addr.sample() == Addr.Neg)
      case _ => assert(false)
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 2,
      Seq("service", "updates") -> 4,
      Seq("service", "closes") -> 2,
      Seq("lookups") -> 1
    ))
  }

  test("Namer filters by tag (case-insensitive)") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None =>
          val rsp = Map("consul" -> Seq(), "servicename" -> Seq("MASter", "STAging"))
          Future.value(Indexed(rsp, Some("1")))
        case _ => Future.never // don't respond to blocking index calls
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
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

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.tagged(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("acme.co"),
      setHost = false,
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/masTER/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (addrs, _) =>
      assert(addrs.size == 1)
      assert(addrs.head.toString.contains("192.168.1.35:8080"))
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("service", "closes") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer returns authority in bound address metadata when setHost is true") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
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
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = blockingIndex match {
        case Some("0") | None =>
          Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case _ => Future.never //don't respond to blocking index calls
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("consul.acme.co"),
      setHost = true,
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (_, metadata) =>
      assert(metadata == Addr.Metadata(Metadata.authority -> "servicename.service.dc1.consul.acme.co"))
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("service", "closes") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer returns authority with tag in bound address metadata when setHost is true and tag is provided") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
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
        consistency: Option[ConsistencyMode] = None,
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

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.tagged(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("consul.acme.co"),
      setHost = true,
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/master/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (_, metadata) =>
      assert(
        metadata == Addr.Metadata(
          Metadata.authority -> "master.servicename.service.dc1.consul.acme.co"
        )
      )
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("service", "closes") -> 1,
      Seq("lookups") -> 1
    ))
  }
}
