package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant._
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.finagle.stack.{Endpoint, nilStack}
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.router.RoutingFactory.IdentifiedRequest
import io.buoyant.test.FunSuite

// This is a sort of end-to-end test, but is intended to improve test
// coverage of Router.scala
class RouterTest extends FunSuite {

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
      stk.make(params + param.Label(label))
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
      in => Future.value(
        new IdentifiedRequest[String](Dst.Path(pfx ++ Path.Utf8(in), baseDtab(), Dtab.local), in)
      )
    }
  }

  test("client labeling") {
    @volatile var label: Option[String] = None
    val labelModule: Stackable[ServiceFactory[String, Int]] =
      new Stack.Module1[param.Label, ServiceFactory[String, Int]] {
        def role = Stack.Role("Label")
        def description = "captures a client's label"
        def make(_label: param.Label, next: ServiceFactory[String, Int]) = {
          label = Some(_label.label)
          next
        }
      }

    val namer = new NameInterpreter {
      def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
        val id: Any = path match {
          case Path.Utf8("svc", "0") => Path.Utf8("some", "path")
          case Path.Utf8("svc", "1") => "string"
          case _ => new {}
        }
        Activity.value(NameTree.Leaf(Name.Bound(Var.value(Addr.Pending), id)))
      }
    }

    val factory = TestRouter()
      .configured(DstBindingFactory.Namer(namer))
      .withClientStack(labelModule +: nilStack)
      .factory()
    val service = await(factory())

    label = None
    assert(await(service("0")) == 0)
    assert(label == Some("some/path"))

    label = None
    assert(await(service("1")) == 1)
    assert(label == Some("string"))

    label = None
    assert(await(service("3")) == 3)
    assert(label == Some("unknown"))
  }
}
