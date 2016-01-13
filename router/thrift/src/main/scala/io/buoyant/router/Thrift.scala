package io.buoyant.router

import com.twitter.finagle.{Thrift => FinagleThrift, Server => FinagleServer, _}
import com.twitter.finagle.buoyant._
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.thrift.ThriftClientRequest
import com.twitter.util._
import java.net.SocketAddress

object Thrift extends Router[ThriftClientRequest, Array[Byte]]
  with FinagleServer[Array[Byte], Array[Byte]] {

  object param {
    /**
     * XXX this should be removed once finagle subsumes this:
     * https://github.com/twitter/finagle/pull/451
     */
    case class Framed(enabled: Boolean)
    implicit object Framed extends Stack.Param[Framed] {
      val default = Framed(true)
    }
  }

  object Router {
    val pathStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] =
      StackRouter.newPathStack[ThriftClientRequest, Array[Byte]]

    val boundStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] =
      StackRouter.newBoundStack[ThriftClientRequest, Array[Byte]]

    val client: StackClient[ThriftClientRequest, Array[Byte]] =
      FinagleThrift.client
        .transformed(StackRouter.Client.mkStack(_))

    val defaultParams: Stack.Params =
      StackRouter.defaultParams +
        ProtocolLibrary("thrift")

    /** Statically assigns a destination to all requests. */
    case class Identifier(
      name: Path = Path.empty,
      dtab: () => Dtab = () => Dtab.base
    ) extends RoutingFactory.Identifier[ThriftClientRequest] {
      def apply(req: ThriftClientRequest): Future[Dst] =
        Future.value(Dst.Path(name, dtab(), Dtab.local))
    }

    /**
     * Wraps a byte-array payload as a two-way ThriftClientRequest.
     */
    object IngestingFilter extends Filter[Array[Byte], Array[Byte], ThriftClientRequest, Array[Byte]] {
      def apply(bytes: Array[Byte], svc: Service[ThriftClientRequest, Array[Byte]]) =
        svc(new ThriftClientRequest(bytes, false))
    }
  }

  /**
   * @todo framed should be a Stack.Param
   */
  case class Router(
    pathStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] = Router.pathStack,
    boundStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] = Router.boundStack,
    client0: StackClient[ThriftClientRequest, Array[Byte]] = Router.client,
    params: Stack.Params = Router.defaultParams
  ) extends StdStackRouter[ThriftClientRequest, Array[Byte], Router] {

    val param.Framed(framed) = params[param.Framed]
    protected val client =
      FinagleThrift.client.copy(framed = framed).withStack(client0.stack)

    protected def copy1(
      pathStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] = this.pathStack,
      boundStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] = this.boundStack,
      client: StackClient[ThriftClientRequest, Array[Byte]] = this.client,
      params: Stack.Params = this.params
    ): Router = copy(pathStack, boundStack, client, params)

    protected def newIdentifier(): RoutingFactory.Identifier[ThriftClientRequest] = {
      val RoutingFactory.DstPrefix(pfx) = params[RoutingFactory.DstPrefix]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]
      Router.Identifier(pfx, baseDtab)
    }
  }

  val router = Router()
  def factory(): ServiceFactory[ThriftClientRequest, Array[Byte]] =
    router.factory()

  object Server {
    val stack: Stack[ServiceFactory[Array[Byte], Array[Byte]]] =
      FinagleThrift.Server.stack

    val defaultParams: Stack.Params =
      StackServer.defaultParams +
        ProtocolLibrary("thrift")
  }

  /** Wraps Finagle-Thrift's server type with support for the [[param.Framed]] Param. */
  case class Server(
    underlying: FinagleThrift.Server = FinagleThrift.Server(Server.stack)
  ) extends StackServer[Array[Byte], Array[Byte]] {
    def stack = underlying.stack
    def params = underlying.params
    def framed = params[param.Framed].enabled

    override def withStack(stack: Stack[ServiceFactory[Array[Byte], Array[Byte]]]): Server =
      copy(underlying = underlying.withStack(stack))

    override def withParams(ps: Stack.Params): Server =
      copy(underlying = underlying.withParams(ps))

    override def configured[P: Stack.Param](p: P): Server =
      withParams(params + p)

    override def configured[P](psp: (P, Stack.Param[P])): Server = {
      val (p, sp) = psp
      configured(p)(sp)
    }

    def serve(
      addr: SocketAddress,
      factory: ServiceFactory[Array[Byte], Array[Byte]]
    ): ListeningServer =
      underlying.copy(framed = framed).serve(addr, factory)
  }

  val server = Server()

  def serve(
    addr: SocketAddress,
    factory: ServiceFactory[Array[Byte], Array[Byte]]
  ): ListeningServer =
    server.serve(addr, factory)
}
