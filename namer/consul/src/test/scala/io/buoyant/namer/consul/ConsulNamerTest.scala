package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Promise}
import io.buoyant.consul.v1._
import io.buoyant.namer.{ConfiguredDtabNamer, Metadata}
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
  val testServiceNode2 = ServiceNode(
    Some("node2"),
    Some("192.168.1.36"),
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
      ): Future[Indexed[Map[String, Seq[String]]]] = Future.exception(ChannelWriteException(None))
    }
    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(testPath, new TestApi(), new TestAgentApi("acme.co"), stats = stats)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assert(state == Activity.Failed(ChannelWriteException(None)))
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
        case Some("0") | None =>
          val node = ServiceNode(Some("foobar"), None, None, None, None, Some("127.0.0.1"), Some(8888))
          Future.value(Indexed(Seq(node), Some("1")))
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
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
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
      ()
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
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
      ()
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 3,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Addrs update when blocking call for serviceNodes returns") {
    val scaleUp = new Promise[Unit]
    val scaleToEmpty = new Promise[Unit]

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
          scaleUp before Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode, testServiceNode2), Some("2")))
        case Some("2") =>
          scaleToEmpty before Future.value(Indexed[Seq[ServiceNode]](Seq.empty, Some("3")))
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
    val interpreter = ConfiguredDtabNamer(Activity.value(Dtab.empty), Seq(Path.read("/#/io.l5d.consul") -> namer))
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    @volatile var nameTreeUpdates: Int = 0
    interpreter.bind(Dtab.empty, Path.read("/#/io.l5d.consul/dc1/servicename/residual")).states respond { s =>
      nameTreeUpdates += 1
      state = s
    }

    assertOnAddrs(state) { (addrs, _) => assert(addrs.size == 1); () }
    assert(nameTreeUpdates == 1)

    scaleUp.setDone()

    // NameTree activity should not have updated due to scale up
    assert(nameTreeUpdates == 1)
    assertOnAddrs(state) { (addrs, _) => assert(addrs.size == 2); () }

    scaleToEmpty.setDone()

    // NameTree activity DOES update when replica set becomes empty
    assert(nameTreeUpdates == 2)
    assert(state == Activity.Ok(NameTree.Neg))

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 3,
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
      ()
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
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
      ()
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
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
      ()
    }

    assert(stats.counters == Map(
      Seq("dc", "opens") -> 1,
      Seq("dc", "updates") -> 1,
      Seq("dc", "adds") -> 4,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer falls back to last observed good state on serviceNodes failure") {
    class TestApi extends CatalogApi(null, "/v1") {
      @volatile var alreadyFailed = false

      override def serviceMap(
        datacenter: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Map[String, Seq[String]]]] = blockingIndex match {
        case Some("0") | None =>
          val rsp = Map("consul" -> Seq(), "servicename" -> Seq("master", "staging"))
          Future.value(Indexed(rsp, Some("1")))
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
        case Some("0") | None =>
          Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
        case _ if alreadyFailed => Future.never
        case _ =>
          alreadyFailed = true
          Future.exception(new Exception("something is wrong"))

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
      ()
    }

  }
  test("Namer falls back to most recent observed good state on serviceNodes failure") {
    val scaleUp = new Promise[Unit]
    val doFail = new Promise[Unit]
    val scaleToEmpty = new Promise[Unit]
    @volatile var didFail = false
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
          scaleUp before Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode, testServiceNode2), Some("2")))
        case Some("2") =>
          doFail before (
            if (didFail)
              scaleToEmpty before Future.value(Indexed[Seq[ServiceNode]](Seq.empty, Some("3")))
            else {
              didFail = true
              Future.exception(new Exception("something bad happened"))
            }
          )
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
    val interpreter = ConfiguredDtabNamer(Activity.value(Dtab.empty), Seq(Path.read("/#/io.l5d.consul") -> namer))
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    @volatile var nameTreeUpdates: Int = 0
    interpreter.bind(Dtab.empty, Path.read("/#/io.l5d.consul/dc1/servicename/residual")).states respond { s =>
      nameTreeUpdates += 1
      state = s
    }

    withClue("before failure") {
      assertOnAddrs(state) { (addrs, _) => assert(addrs.size == 1); () }

      scaleUp.setDone()
      assertOnAddrs(state) { (addrs, _) => assert(addrs.size == 2); () }
    }

    withClue("after failure") {
      doFail.setDone()
      assertOnAddrs(state) { (addrs, _) =>
        assert(addrs.size == 2, "namer fell back to wrong state")
        ()
      }

      scaleToEmpty.setDone()
      assert(
        state == Activity.Ok(NameTree.Neg),
        "namer did not update after falling back"
      )
    }
  }
}
