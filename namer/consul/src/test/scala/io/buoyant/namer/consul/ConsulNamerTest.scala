package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.http.{Response, Status}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util.{Config => _, _}
import io.buoyant.consul.v1._
import io.buoyant.namer.{ConfiguredDtabNamer, Metadata}
import io.buoyant.test.{Awaits, FunSuite}

class ConsulNamerTest extends FunSuite with Awaits {

  setLogLevel(Level.ERROR)

  val testPath = Path.read("/test")
  val testServiceNode = ServiceNode(
    Some("node"),
    Some("192.168.1.35"),
    Some("servicename"),
    Some("servicename"),
    Some(Seq.empty),
    Some(""),
    Some(8080),
    Some(HealthStatus.Passing)
  )
  val testServiceNode2 = ServiceNode(
    Some("node2"),
    Some("192.168.1.36"),
    Some("servicename"),
    Some("servicename"),
    Some(Seq.empty),
    Some(""),
    Some(8080),
    Some(HealthStatus.Passing)
  )

  val emptyRequest = http.Request()
  def testCall[Rep](call: => Future[Rep]) = ApiCall(emptyRequest, _ => call)

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
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String] = None,
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall(Future.never)
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

    Time.withCurrentTimeFrozen { _ =>
      namer.lookup(Path.read("/dc1/servicename/residual")).states respond {
        state = _
      }
      assert(state == Activity.Pending)
      assert(
        stats.counters == Map(
          Seq("service", "updates") -> 0,
          Seq("service", "errors") -> 0,
          Seq("service", "closes") -> 0,
          Seq("service", "cached") -> 1,
          Seq("service", "opens") -> 1,
          Seq("lookups") -> 1
        )
      )
    }
  }

  test("Namer stays pending if the consul api cannot be reached") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String] = None,
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall(Future.exception(ChannelWriteException(None)))
    }
    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(testPath, new TestApi(), new TestAgentApi("acme.co"), stats = stats)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    Time.withCurrentTimeFrozen { _ =>
      namer.lookup(Path.read("/dc1/servicename/residual")).states respond {
        state = _
      }

      assert(state == Activity.Pending)
      assert(
        stats.counters == Map(
          Seq("service", "updates") -> 0,
          Seq("service", "closes") -> 0,
          Seq("service", "cached") -> 1,
          Seq("service", "opens") -> 1,
          Seq("service", "errors") -> 1,
          Seq("lookups") -> 1
        )
      )
    }
  }

  test("Namer returns Neg when servicename does not exist") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
          blockingIndex match {
          case Some("0") | None => Future.value(Indexed(Seq.empty, Some("1")))
          case _ => Future.never //don't respond to blocking index calls
        }
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(testPath, new TestApi(), new TestAgentApi("acme.co"), stats = stats)
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending

    namer.lookup(Path.read("/dc1/nosuchservice/residual")).states respond { state = _ }

    assert(state == Activity.Ok(NameTree.Neg))
    assert(stats.counters == Map(
      Seq("service", "errors") -> 0,
      Seq("service", "closes") -> 0,
      Seq("service", "cached") -> 1,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer updates when serviceNodes blocking calls return") {
    val blockingCallResponder = new Promise[Unit]

    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
          blockingIndex match {
          case Some("0") | None =>
            Future.value(Indexed(Seq.empty, Some("1")))
          case Some("1") =>
            val node = ServiceNode(Some("foobar"), None, None, None, None, Some("127.0.0.1"), Some(8888), None)
            blockingCallResponder before Future.value(Indexed(Seq(node), Some("2")))
          case _ => Future.never
        }
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
      Seq("service", "errors") -> 0,
      Seq("service", "closes") -> 0,
      Seq("service", "cached") -> 1,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 2,
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
      ): ApiCall[IndexedServiceMap] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            val rsp = Map("consul" -> Seq(), "serviceNAME" -> Seq("master", "staging"))
            Future.value(Indexed(rsp, Some("1")))
          case _ => Future.never //don't respond to blocking index calls
        }
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
          case _ => Future.never // don't respond to blocking index calls
        }
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
      Seq("service", "errors") -> 0,
      Seq("service", "closes") -> 0,
      Seq("service", "cached") -> 1,
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
      ): ApiCall[IndexedServiceMap] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            val rsp = datacenter match {
              case Some("dc1") => Map("servicename" -> Seq("master", "staging"))
              case _ => Map.empty[String, Seq[String]]
            }
            Future.value(Indexed(rsp, Some("1")))
          case _ => Future.never // don't respond to blocking index calls
        }
      }

      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
          case _ => Future.never // don't respond to blocking index calls
        }
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
      Seq("service", "errors") -> 0,
      Seq("service", "closes") -> 0,
      Seq("service", "cached") -> 1,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Addrs update when blocking call for serviceNodes returns") {
    val scaleUp = new Promise[Unit]
    val scaleToEmpty = new Promise[Unit]

    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
          case Some("1") =>
            scaleUp before Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode, testServiceNode2), Some("2")))
          case Some("2") =>
            scaleToEmpty before Future.value(Indexed[Seq[ServiceNode]](Seq.empty, Some("3")))
          case _ => Future.never
        }
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
      Seq("service", "errors") -> 0,
      Seq("service", "closes") -> 0,
      Seq("service", "cached") -> 1,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 3,
      Seq("lookups") -> 1
    ))
  }

  test("Namer filters by tag (case-insensitive)") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            tag match {
              case Some("master") =>
                Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
              case _ => Future.value(Indexed(Nil, Some("1")))
            }
          case _ => new Promise // don't respond to blocking index calls
        }
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
      Seq("service", "errors") -> 0,
      Seq("service", "closes") -> 0,
      Seq("service", "cached") -> 1,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer returns authority in bound address metadata when setHost is true") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
          case _ => Future.never //don't respond to blocking index calls
        }
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
      Seq("service", "errors") -> 0,
      Seq("service", "closes") -> 0,
      Seq("service", "cached") -> 1,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer returns authority with tag in bound address metadata when setHost is true and tag is provided") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            tag match {
              case Some("master") =>
                Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
              case _ => Future.value(Indexed(Nil, Some("1")))
            }
          case _ => Future.never //don't respond to blocking index calls
        }
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
      Seq("service", "errors") -> 0,
      Seq("service", "closes") -> 0,
      Seq("service", "cached") -> 1,
      Seq("service", "opens") -> 1,
      Seq("service", "updates") -> 1,
      Seq("lookups") -> 1
    ))
  }

  test("Namer falls back to last observed good state on serviceNodes failure") {
    class TestApi extends CatalogApi(null, "/v1") {
      @volatile var alreadyFailed = false
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
          case Some("0") | None =>
            Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
          case _ if alreadyFailed => Future.never
          case _ =>
            alreadyFailed = true
            Future.exception(new Exception("something is wrong"))

        }
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

    assertOnAddrs(state){(addrs, metadata) =>
      assert(addrs.size == 1)
      assert(metadata == Addr.Metadata(Metadata.authority -> "servicename.service.dc1.consul.acme.co"))
      ()
    }
  }

  test("Namer state returns Pending then Bound then remains Bound when a datacenter becomes available and then becomes unavailable") {
    val datacenterWillBeUnavailable = new Promise[Unit]
    val datacenterIsAvailable = new Promise[Unit]
    @volatile var datacenterIsUp = false
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String],
        blockingIndex: Option[String],
        consistency: Option[ConsistencyMode],
        retry: Boolean
      ): ApiCall[IndexedServiceNodes] = testCall{
        blockingIndex match {
          case Some("0") | None =>
            if (datacenterIsUp)
              datacenterIsAvailable before Future.value(
                Indexed[Seq[ServiceNode]](Seq(testServiceNode, testServiceNode2), Some("1"))
              )
            else {
              datacenterIsUp = true
              Future.exception(new Exception("datacenter does not exist yet"))
            }
          case Some("1") =>
            val rsp = Response(Status.InternalServerError)
            rsp.contentString = "No path to datacenter"
            datacenterWillBeUnavailable before Future.exception(UnexpectedResponse(rsp))
          case _ =>
            Future.never
        }
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("consul.acme.co"),
      setHost = false,
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/#/io.l5d.consul/dc1/servicename/residual")).states respond { s =>
      state = s
    }

    withClue("before datacenter is available") {
      eventually {
        assert(state == Activity.Pending)
      }

      datacenterIsAvailable.setDone()
      eventually {
        assertOnAddrs(state) { (addrs, _) => assert(addrs.size == 2); () }
      }

    }

    withClue("during datacenter crash") {
      datacenterWillBeUnavailable.setDone()
      eventually {
        assertOnAddrs(state) { (addrs, _) => assert(addrs.size == 2); () }
      }
    }
  }


  test("Namer falls to Neg state on serviceNodes failure after preceding successful serviceNode requests") {
    val scaleUp = new Promise[Unit]
    val doFail = new Promise[Unit]
    val scaleToEmpty = new Promise[Unit]
    val scenarioComplete = new Promise[Unit]
    @volatile var didFail = false
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
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
                Future.exception(new Exception("serviceNode failure"))
              }
            )
          case _ =>
            scenarioComplete.setDone()
            Future.never
        }
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
      assertOnAddrs(state){ (addrs, _) => assert(addrs.size == 2); () }

      scaleToEmpty.setDone()
      await(scenarioComplete)
      assert(state == Activity.Ok(NameTree.Neg)) // address list is empty so resolve to neg
    }
  }

  test("Namer doesn't poll consul again after observation is closed") {
    @volatile var reqs = 0
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
        blockingIndex match {
          case Some("0") | None if reqs == 0 =>
            reqs += 1
            Future.value(Indexed[Seq[ServiceNode]](Seq(testServiceNode), Some("1")))
          case Some(_) =>
            reqs += 1
            // when the activity is closed, we need to set the state of the future
            // to the exception, the way a real future would (which Future.never would not do).
            val promise = new Promise[Indexed[Seq[ServiceNode]]]()
            promise.setInterruptHandler { case e => promise.setException(e) }
            promise
        }
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
    @volatile var nameTreeUpdates: Int = 0
    val closer =
      namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }
    assert(reqs == 2)
    await(closer.close())
    withClue("after close") { assert(reqs == 2, "Consul observed by closed activity!") }
    assert(stats.counters.get(Seq("service", "opens")).contains(1))
    assert(stats.counters.get(Seq("service", "closes")).contains(1))
    assert(stats.counters.get(Seq("service", "errors")).forall(_ == 0))
  }
  test("Namer returns weighted bound address metadata when service has configured weight tag") {
    class TestApi extends CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = testCall {
          blockingIndex match {
          case Some("0") | None =>
            val node = testServiceNode.copy(ServiceTags = Some(Seq("production", "primary")))
            Future.value(Indexed[Seq[ServiceNode]](Seq(node), Some("1")))
          case _ => Future.never //don't respond to blocking index calls
        }
      }
    }

    val stats = new InMemoryStatsReceiver
    val namer = ConsulNamer.untagged(
      Path.read("/test"),
      new TestApi(),
      new TestAgentApi("consul.acme.co"),
      weights = Map("primary" -> 100),
      stats = stats
    )
    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    namer.lookup(Path.read("/dc1/servicename/residual")).states respond { state = _ }

    assertOnAddrs(state) { (addrs, _) =>
      assert(addrs.size == 1)
      val inet = addrs.head.asInstanceOf[Address.Inet]
      assert(inet.metadata == Map(Metadata.endpointWeight -> 100))
      ()
    }

    assert(stats.counters.get(Seq("service", "opens")).contains(1))
    assert(stats.counters.get(Seq("service", "updates")).contains(1))
    assert(stats.counters.get(Seq("lookups")).contains(1))
  }
}
