package io.buoyant.namer.consul

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Addr, Address, Failure, IndividualRequestTimeoutException}
import com.twitter.util._
import io.buoyant.consul.v1._
import io.buoyant.namer.consul.SvcAddr.Stats
import io.buoyant.namer.InstrumentedVar
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.{FunSuite, Matchers}

class SvcAddrTest extends FunSuite with Matchers with Awaits {

  implicit val timer: Timer = DefaultTimer

  val hangForLongBackoff = Stream.continually(Duration.Top)
  val emptyRequest = Request()

  def service(host: String = "8.8.8.8", port: Int = 53): (ServiceNode, InetSocketAddress) =
    (
      ServiceNode(
        Some("node"),
        Some(host),
        Some("servicename"),
        Some("servicename"),
        Some(Seq.empty),
        Some(""),
        Some(port),
        Some(HealthStatus.Passing)
      ),
        new InetSocketAddress(host, port)
    )

  def apiStub(stubFn: (String, Option[String], Option[String], Option[String], Option[ConsistencyMode], Boolean) => Future[IndexedServiceNodes]) =
    new CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): ApiCall[IndexedServiceNodes] = ApiCall(emptyRequest, _ => stubFn(serviceName, datacenter, tag, blockingIndex, consistency, retry))
    }

  /**
   * Creates CatalogApi stub that serves responses in sequence.
   * Returns CatalogApi and a Future that is satisfied after all responses are served.
   */
  def scriptedApiStub(scriptedResponses: Future[IndexedServiceNodes]*): (CatalogApi, Future[Unit]) = {
    val servedAllResponses = Promise[Unit]()
    var remaining = scriptedResponses.toSeq
    apiStub { (_, _, _, _, _, _) =>
      remaining match {
        case head +: tail =>
          remaining = tail
          head
        case _ =>
          servedAllResponses.setDone()
          Future.never
      }
    } -> servedAllResponses
  }

  object singletonSet {
    def unapply[T](arg: Set[T]): Option[T]= arg.headOption
  }

  test("should keep last known Addr.Bound value on generic error") {
    // given
    val (serviceNode, serviceAddr) = service()
    val (api, servedAll) = scriptedApiStub(
      Future.value(Indexed[Seq[ServiceNode]](Seq(serviceNode), Some("1"))),
      Future.exception(new Throwable("whatever is thrown we catch"))
    )

    // when
    val svcAddrVar: InstrumentedVar[Addr] = SvcAddr(
      api,
      Stream.continually(1.millis),
      "dc1",
      SvcKey("svc", None),
      None,
      None,
      None,
      Map.empty,
      Stats(NullStatsReceiver),
      new PollState
    )
    // observe Var so it is not dormant
    svcAddrVar.underlying.changes.respond(_ => ())

    // then
    await(servedAll)

    svcAddrVar.running shouldBe true
    svcAddrVar.lastStartedAt shouldBe 'defined
    svcAddrVar.lastStoppedAt should not be 'defined
    svcAddrVar.lastUpdatedAt shouldBe 'defined

    svcAddrVar.underlying.sample() should matchPattern {
      case Addr.Bound(singletonSet(Address.Inet(addr, _)), _) if addr == serviceAddr => ()
    }
  }

  test("should keep last known Addr.Bound value on the 'No path to datacenter' error") {
    // given
    val (serviceNode, serviceAddr) = service()

    val rsp = Response(Version.Http11, Status.InternalServerError)
    rsp.contentString = "No path to datacenter"
    val (api, servedAll) = scriptedApiStub(
      Future.value(Indexed[Seq[ServiceNode]](Seq(serviceNode), Some("1"))),
      Future.exception(UnexpectedResponse(rsp))
    )

    // when
    val svcAddrVar: InstrumentedVar[Addr] = SvcAddr(
      api,
      Stream.continually(1.millis),
      "dc1",
      SvcKey("svc", None),
      None,
      None,
      None,
      Map.empty,
      Stats(NullStatsReceiver),
      new PollState
    )
    // observe Var so it is not dormant
    svcAddrVar.underlying.changes.respond(_ => ())

    // then
    await(servedAll)

    svcAddrVar.running shouldBe true
    svcAddrVar.lastStartedAt shouldBe 'defined
    svcAddrVar.lastStoppedAt should not be 'defined
    svcAddrVar.lastUpdatedAt shouldBe 'defined

    svcAddrVar.underlying.sample() should matchPattern {
      case Addr.Bound(singletonSet(Address.Inet(addr, _)), _) if addr == serviceAddr => ()
    }
  }

  test("should return Addr.Neg on the 'No path to datacenter' error if no previous state exist") {
    // given
    val (serviceNode, serviceAddr) = service()

    val rsp = Response(Version.Http11, Status.InternalServerError)
    rsp.contentString = "No path to datacenter"
    val (api, servedAll) = scriptedApiStub(
      Future.exception(UnexpectedResponse(rsp))
    )

    // when
    val svcAddrVar: InstrumentedVar[Addr] = SvcAddr(
      api,
      Stream.continually(1.millis),
      "dc1",
      SvcKey("svc", None),
      None,
      None,
      None,
      Map.empty,
      Stats(NullStatsReceiver),
      new PollState
    )
    // observe Var so it is not dormant
    svcAddrVar.underlying.changes.respond(_ => ())

    // then
    await(servedAll)

    svcAddrVar.running shouldBe true
    svcAddrVar.lastStartedAt shouldBe 'defined
    svcAddrVar.lastStoppedAt should not be 'defined
    svcAddrVar.lastUpdatedAt shouldBe 'defined

    svcAddrVar.underlying.sample() should matchPattern {
      case Addr.Neg =>
    }
  }

  test("should retry with backoff on errors") {
    // given
    val numOfRequests = new AtomicInteger()
    val api = apiStub { (_, _, _, _, _, _) =>
      numOfRequests.incrementAndGet()
      Future.exception(new Throwable("whatever is thrown we retry"))
    }
    val numOfRetries = 5
    val retried = new Promise[Unit] // satisfied when backoffs stream reaches hang
    // When the head of a stream is destructured off, the next element is reified.  This means that
    // when the first Duration.Top backoff is used, the next element is evaluated and the retried
    // promise is satisfied.
    lazy val hang: Stream[Duration] = Duration.Top #:: {retried.setDone(); Duration.Top} #:: Stream.empty[Duration]

    val backoffs: Stream[Duration] = Stream.fill(numOfRetries)(10.millis) #::: hang

    // when
    val addr: InstrumentedVar[Addr] = SvcAddr(api, backoffs, "dc1", SvcKey("svc", None), None, None, None, Map.empty, Stats(NullStatsReceiver), new PollState)
    addr.underlying.changes.respond(_ => ())

    // then
    await(retried)
    numOfRequests.intValue() should equal(numOfRetries+1)
  }

  test("should extract nested root cause correctly") {
    // given
    val cause = Failure("cause")
    val failure = Failure("one", Failure("two", Failure("three", cause)))

    // when
    val extracted = SvcAddr.RootCause.unapply(failure)

    // then
    extracted should be(Some(cause))
  }

  test("should extract root cause if there are no nested causes") {
    // given
    val cause = Failure("cause")

    // when
    val extracted = SvcAddr.RootCause.unapply(cause)

    // then
    extracted should be(Some(cause))
  }

  test("should be Addr.Pending before call to consul returns") {
    // given
    val api = apiStub { (_, _, _, _, _, _) =>
      Future.never
    }

    // when
    val addr: InstrumentedVar[Addr] = SvcAddr(api, hangForLongBackoff, "dc1", SvcKey("svc", None), None, None, None, Map.empty, Stats(NullStatsReceiver), new PollState)
    addr.underlying.changes.respond(_ => ())

    // then
    addr.running shouldBe true
    addr.lastStartedAt shouldBe 'defined
    addr.lastStoppedAt should not be 'defined
    addr.lastUpdatedAt should not be 'defined
    addr.underlying.sample() should matchPattern { case Addr.Pending => () }
  }

  test("should be Addr.Neg when unexpected error occurs initially") {
    // given
    val api = apiStub { (_, _, _, _, _, _) =>
      Future.exception(new Throwable("No path to datacenter"))
    }

    // when
    val addr: InstrumentedVar[Addr] = SvcAddr(api, hangForLongBackoff, "dc1", SvcKey("svc", None), None, None, None, Map.empty, Stats(NullStatsReceiver), new PollState)
    addr.underlying.changes.respond(_ => ())

    // then
    addr.running shouldBe true
    addr.lastStartedAt shouldBe 'defined
    addr.lastStoppedAt should not be 'defined
    addr.lastUpdatedAt shouldBe 'defined
    addr.underlying.sample() should matchPattern { case Addr.Neg => () }
  }

  test("should use last known state on api timeout") {
    // given
    val requestCounter = new AtomicInteger()
    val (initServiceUpdate, firstAddr) = service()
    val (secondSvcUpdate, secondAddr) = service("9.9.9.9", 1024)
    val ttl = 1.minute
    @volatile var changes: Addr = Addr.Pending

    val timer = new MockTimer

    val api = apiStub { (_, _, _, _, _, _) =>
      val count = requestCounter.incrementAndGet()
      count match {
        case 1 =>
          val response = Indexed[Seq[ServiceNode]](Seq(initServiceUpdate), Some("1"))
          Future.sleep(5.minutes)(timer).before(Future.value(response))
        case 2 =>
          val rspF = Future.sleep(11.minutes)(timer)
            .before(Future.exception(new IndividualRequestTimeoutException(10.minutes)))
          rspF
        case 3 =>
          Future.sleep(1.minute)(timer)
            .before(Future.value(Indexed[Seq[ServiceNode]](Seq(secondSvcUpdate), Some("2"))))
      }
    }

    // when
    Time.withCurrentTimeFrozen { tc =>

      val addr: InstrumentedVar[Addr] = SvcAddr(
        api,
        Stream.fill(10)(ttl),
        "dc1",
        SvcKey("svc", None),
        None,
        None,
        None,
        Map.empty,
        Stats(NullStatsReceiver),
        new PollState
      )(timer)

      addr.underlying.changes.respond {
        changes = _
      }

      // then
      tc.advance(5.minutes)
      timer.tick()
      changes match {
        case Addr.Bound(addrs, _) => addrs.head shouldBe Address
          .Inet(firstAddr, Addr.Metadata("endpoint_addr_weight" -> 1.0))
        case _ => fail("received unexpected Addr on initial service discovery")
      }

      tc.advance(12.minutes)
      timer.tick()
      changes match {
        case Addr.Bound(addrs, _) => addrs.head shouldBe Address
          .Inet(firstAddr, Addr.Metadata("endpoint_addr_weight" -> 1.0))
        case _ => fail("received unexpected Addr on timed out service discovery request")
      }

      // Advance timer to trigger Future.sleep(backoff) in SvcAddr
      tc.advance(1.minutes)
      timer.tick()

      tc.advance(1.minutes)
      timer.tick()

      eventually {
        changes match {
          case Addr.Bound(addrs, _) => addrs.head shouldBe Address
            .Inet(secondAddr, Addr.Metadata("endpoint_addr_weight" -> 1.0))
          case _ => fail("received unexpected Addr on timed out service discovery request")
        }
      }
    }
  }
}
