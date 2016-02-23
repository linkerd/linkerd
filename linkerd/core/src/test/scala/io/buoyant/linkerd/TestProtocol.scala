package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.stack.Endpoint
import com.twitter.util.Future
import io.buoyant.linkerd.TestProtocol.FancyParam
import io.buoyant.linkerd.config.Parser
import io.buoyant.router.{RoutingFactory, StackRouter, StdStackRouter}
import java.net.SocketAddress

abstract class TestProtocol(val name: String) extends ProtocolInitializer.Simple {
  protected type Req = String
  protected type Rsp = String

  private[this] val endpoint = new Stack.Module[ServiceFactory[String, String]] {
    val role = Endpoint
    val description = "echoes"
    val parameters = Seq.empty
    val service = Service.mk[String, String](Future.value)
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

  val defaultServerPort = 13
}

class PlainConfig extends RouterConfig {

  var servers: Seq[ServerConfig] = Nil
  var client: Option[ClientConfig] = None

  @JsonIgnore
  override def protocol: ProtocolInitializer = TestProtocol.Plain
}

case class FancyConfig(fancy: Option[Boolean]) extends RouterConfig {

  var servers: Seq[ServerConfig] = Nil
  var client: Option[ClientConfig] = None

  @JsonIgnore
  override def protocol: ProtocolInitializer = TestProtocol.Fancy

  @JsonIgnore
  override def routerParams: Params = super.routerParams
    .maybeWith(fancy.map(FancyParam(_)))
}

object TestProtocol {

  case class FancyParam(pants: Boolean)
  implicit object FancyParam extends Stack.Param[FancyParam] {
    override def default: FancyParam = FancyParam(false)
  }

  object Plain extends TestProtocol("plain") {
    val configClass = classOf[PlainConfig]
    val configId = "plain"
  }

  object Fancy extends TestProtocol("fancy") {
    val configClass = classOf[FancyConfig]
    val configId = "fancy"
  }
}
