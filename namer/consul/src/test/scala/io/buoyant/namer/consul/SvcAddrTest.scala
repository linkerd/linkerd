package io.buoyant.namer.consul

import com.twitter.conversions.time._
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Addr, Address, Failure}
import com.twitter.util.{Await, Duration, Future, Promise, Timer, Var}
import io.buoyant.consul.v1.{CatalogApi, ConsistencyMode, HealthStatus, Indexed, ServiceNode}
import io.buoyant.namer.consul.SvcAddr.Stats
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.{FunSuite, Matchers}

class SvcAddrTest extends FunSuite with Matchers {

  implicit val timer: Timer = DefaultTimer

  val hangForLongBackoff = Stream.continually(Duration.Top)

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

  def apiStub(stubFn: (String, Option[String], Option[String], Option[String], Option[ConsistencyMode], Boolean) => Future[Indexed[Seq[ServiceNode]]]) =
    new CatalogApi(null, "/v1") {
      override def serviceNodes(
        serviceName: String,
        datacenter: Option[String],
        tag: Option[String] = None,
        blockingIndex: Option[String] = None,
        consistency: Option[ConsistencyMode] = None,
        retry: Boolean = false
      ): Future[Indexed[Seq[ServiceNode]]] = stubFn(serviceName, datacenter, tag, blockingIndex, consistency, retry)
    }

  test("should remain pending on error from start") {
    // givenn
    val invoked = Promise[Unit]()
    val api = apiStub { (_, _, _, _, _, _) =>
      invoked.setDone()
      Future.exception(new Throwable("whatever is thrown we catch"))
    }

    // when
    val addr: Var[Addr] = SvcAddr(api, hangForLongBackoff, "dc1", SvcKey("svc", None), None, None, None, Map.empty, Stats(NullStatsReceiver))
    addr.changes.respond(_ => ())

    // then
    Await.ready(invoked, 1.second)
    addr.sample() should matchPattern { case Addr.Pending => }
  }

  test("should keep last known Addr.Bound value on error") {
    // given
    val invoked = Promise[Unit]()
    val (serviceNode, serviceAddr) = service()
    val response = Indexed[Seq[ServiceNode]](Seq(serviceNode), Some("1"))
    @volatile var stateReturned = false
    val api = apiStub { (_, _, _, _, _, _) =>
      synchronized {
        if (!stateReturned) {
          stateReturned = true
          Future.value(response)
        } else {
          invoked.setDone()
          Future.exception(new Throwable("whatever is thrown we catch"))
        }
      }
    }

    // when
    val addr: Var[Addr] = SvcAddr(api, hangForLongBackoff, "dc1", SvcKey("svc", None), None, None, None, Map.empty, Stats(NullStatsReceiver))
    addr.changes.respond(_ => ())

    // then
    Await.ready(invoked, 1.second)
    addr.sample() match {
      case Addr.Bound(addrSet, _) =>
        addrSet should have size 1
        addrSet.head should matchPattern { case Address.Inet(addr, _) if addr == serviceAddr => }
      case _ => fail("should be Addr.Bound")
    }
  }

  test("should retry with backoff on errors") {
    // given
    val numOfRequests = new AtomicInteger()
    val api = apiStub { (_, _, _, _, _, _) =>
      numOfRequests.incrementAndGet()
      Future.exception(new Throwable("whatever is thrown we retry"))
    }
    val numOfAttempts = 5
    val retried = new Promise[Unit] // satisfied when backoffs stream reaches hang
    lazy val hang: Duration = { retried.setDone(); Duration.Top }
    val backoffs: Stream[Duration] = Stream.fill(numOfAttempts)(10.millis) #::: hang #:: Stream.empty

    // when
    val addr: Var[Addr] = SvcAddr(api, backoffs, "dc1", SvcKey("svc", None), None, None, None, Map.empty, Stats(NullStatsReceiver))
    addr.changes.respond(_ => ())

    // then
    Await.ready(retried, 1.second)
    numOfRequests.intValue() should equal(numOfAttempts)
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

  test("should be Addr.Neg if dc does not exist") {
    // givenn
    val invoked = Promise[Unit]()
    val api = apiStub { (_, _, _, _, _, _) =>
      invoked.setDone()
      Future.exception(new Throwable("No path to datacenter"))
    }

    // when
    val addr: Var[Addr] = SvcAddr(api, hangForLongBackoff, "dc1", SvcKey("svc", None), None, None, None, Map.empty, Stats(NullStatsReceiver))
    addr.changes.respond(_ => ())

    // then
    Await.ready(invoked, 1.second)
    addr.sample() should matchPattern { case Addr.Neg => }
  }
}
