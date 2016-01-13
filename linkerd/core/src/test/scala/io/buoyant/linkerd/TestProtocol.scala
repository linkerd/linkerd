package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.stack.Endpoint
import com.twitter.util.Future
import io.buoyant.router.{RoutingFactory, StackRouter, StdStackRouter}
import java.net.SocketAddress

class TestProtocol(val name: String) extends ProtocolInitializer.Simple {
  protected type Req = String
  protected type Rsp = String

  private[this] val endpoint = new Stack.Module[ServiceFactory[String, String]] {
    val role = Endpoint
    val description = "echoes"
    val parameters = Seq.empty
    val service = Service.mk[String, String](Future.value(_))
    val factory = ServiceFactory.const(service)
    def make(params: Stack.Params, next: Stack[ServiceFactory[String, String]]) =
      Stack.Leaf(this, factory)
  }

  private[this] val defaultPathStack = StackRouter.newPathStack[String, String]
  private[this] val defaultBoundStack = StackRouter.newBoundStack[String, String]
  private[this] val defaultClientStack = StackRouter.Client.mkStack(
    StackClient.newStack[String, String]
      .replace(endpoint.role, endpoint)
  )
  private[this] val defaultParams = StackRouter.defaultParams

  private[this] case class Client(
    stack: Stack[ServiceFactory[String, String]] = defaultClientStack,
    params: Stack.Params = defaultParams
  ) extends StackClient[String, String] {
    override def withStack(stack: Stack[ServiceFactory[String, String]]) =
      copy(stack = stack)

    override def withParams(ps: Stack.Params) =
      copy(params = ps)

    override def configured[P: Stack.Param](p: P) =
      withParams(params + p)

    override def configured[P](psp: (P, Stack.Param[P])) = {
      val (p, sp) = psp
      configured(p)(sp)
    }

    def newClient(dest: Name, label: String) =
      stack.make(params + param.Label(label))
    def newService(dest: Name, label: String) =
      new FactoryToService(stack.make(params + FactoryToService.Enabled(true)))
  }

  private[this] case class TestRouter(
    pathStack: Stack[ServiceFactory[String, String]] = defaultPathStack,
    boundStack: Stack[ServiceFactory[String, String]] = defaultBoundStack,
    client: StackClient[String, String] = Client(),
    params: Stack.Params = defaultParams
  ) extends StdStackRouter[String, String, TestRouter] {
    override protected def copy1(
      pathStack: Stack[ServiceFactory[String, String]] = pathStack,
      boundStack: Stack[ServiceFactory[String, String]] = boundStack,
      client: StackClient[String, String] = client,
      params: Stack.Params = params
    ): TestRouter = copy(pathStack, boundStack, client, params)

    def newIdentifier() = { req: String =>
      val RoutingFactory.BaseDtab(dtab) = params[RoutingFactory.BaseDtab]
      val RoutingFactory.DstPrefix(pfx) = params[RoutingFactory.DstPrefix]
      val path = pfx ++ Path.read(req)
      Future.value(Dst.Path(path, dtab(), Dtab.local))
    }
  }

  protected val defaultRouter: StackRouter[Req, Rsp] = TestRouter()

  private[this] case class TestServer(
    stack: Stack[ServiceFactory[String, String]] = StackRouter.Server.newStack,
    params: Stack.Params = StackServer.defaultParams
  ) extends StackServer[String, String] {

    /** A new StackServer with the provided Stack. */
    def withStack(stack: Stack[ServiceFactory[String, String]]) =
      copy(stack = stack)

    def withParams(ps: Stack.Params) =
      copy(params = ps)

    override def configured[P: Stack.Param](p: P) =
      withParams(params + p)

    override def configured[P](psp: (P, Stack.Param[P])) = {
      val (p, sp) = psp
      configured(p)(sp)
    }

    def serve(addr: SocketAddress, f: ServiceFactory[String, String]) =
      ??? // not implemented
  }

  protected val defaultServer: StackServer[Req, Rsp] = TestServer()
    .configured(Server.Port(13))
}

object TestProtocol {
  object Plain extends TestProtocol("plain")

  object Fancy extends TestProtocol("fancy") {

    case class Pants(fancy: Boolean)
    implicit object Pants extends Stack.Param[Pants] {
      val default = Pants(false)
    }

    override val routerParamsParser =
      Parsing.Param.Boolean("fancyRouter") { fancy =>
        Pants(fancy)
      }

    override val serverParamsParser =
      Parsing.Param.Boolean("fancyServer") { fancy =>
        Pants(fancy)
      }
  }

  val DefaultInitializers = ProtocolInitializers(Plain, Fancy)
}
