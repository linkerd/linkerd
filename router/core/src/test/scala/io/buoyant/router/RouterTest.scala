package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant._
import com.twitter.finagle.client.{StackClient, StdStackClient}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.service.{Backoff, Retries, RetryBudget}
import com.twitter.finagle.stack.{Endpoint, nilStack}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Activity, Duration, Future, MockTimer, Return, Throw, Time, Try, Var}
import io.buoyant.test.{Exceptions, Awaits}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.FunSuite

// This is a sort of end-to-end test, but is intended to improve test
// coverage of Router.scala
class RouterTest extends FunSuite with Awaits with Exceptions {

  val strToInt = Service.mk[String, Int] { s => Future(s.toInt) }
  val strToIntFactory = ServiceFactory.const(strToInt)

  case class TestClient(
    stack: Stack[ServiceFactory[String, Int]] = nilStack,
    params: Stack.Params = StackRouter.defaultParams
  ) extends StackClient[String, Int] {
    def withStack(stack: Stack[ServiceFactory[String, Int]]): StackClient[String, Int] =
      copy(stack = stack)

    def withParams(ps: Stack.Params): StackClient[String, Int] =
      copy(params = ps)

    def newClient(name: Name, label: String) = {
      val stk = stack ++ Stack.Leaf(Endpoint, strToIntFactory)
      stk.make(params)
    }

    def newService(name: Name, label: String) = ???
  }

  case class TestRouter(
    pathStack: Stack[ServiceFactory[String, Int]] = nilStack,
    boundStack: Stack[ServiceFactory[String, Int]] = nilStack,
    client: StackClient[String, Int] = TestClient(),
    params: Stack.Params = StackRouter.defaultParams
  ) extends StdStackRouter[String, Int, TestRouter] {

    protected def copy1(
      pathStack: Stack[ServiceFactory[String, Int]] = this.pathStack,
      boundStack: Stack[ServiceFactory[String, Int]] = this.boundStack,
      client: StackClient[String, Int] = this.client,
      params: Stack.Params = this.params
    ): TestRouter = copy(pathStack, boundStack, client, params)

    protected def newIdentifier(): RoutingFactory.Identifier[String] = {
      val RoutingFactory.DstPrefix(pfx) = params[RoutingFactory.DstPrefix]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]
      in => Future.value(Dst.Path(pfx ++ Path.Utf8(in), baseDtab(), Dtab.local))
    }
  }

  val depositModule: Stackable[ServiceFactory[String, Int]] =
    new Stack.Module1[Retries.Budget, ServiceFactory[String, Int]] {
      def role = Stack.Role("Desposit")
      def description = "deposits into the retry budget"
      def make(_budget: Retries.Budget, next: ServiceFactory[String, Int]) = {
        val Retries.Budget(budget, _) = _budget
        val filter = Filter.mk[String, Int, String, Int] { (s, svc) =>
          budget.deposit()
          svc(s)
        }
        filter andThen next
      }
    }

  test("path retry budget deposits, client retry budget does not") {
    val deposits = new AtomicInteger(0)
    val budget = new RetryBudget {
      def deposit(): Unit = {
        val _ = deposits.incrementAndGet()
      }
      def tryWithdraw() = true
      def balance = 1L
    }
    val namer = new NameInterpreter {
      def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] =
        Activity.value(NameTree.Leaf(Name.Bound(Var.value(Addr.Pending), Path.Utf8("svc"))))
    }

    val factory = TestRouter()
      .configured(Retries.Budget(budget, Backoff.const(Duration.Zero)))
      .configured(DstBindingFactory.Namer(namer))
      .withPathStack(depositModule +: nilStack)
      .withBoundStack(depositModule +: nilStack)
      .withClientStack(depositModule +: nilStack)
      .factory()

    val svc = await(factory())
    assert(deposits.get() == 0)
    assert(await(svc("12")) == 12)
    assert(deposits.get() == 1)
  }
}
