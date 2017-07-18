package io.buoyant.router

import com.twitter.finagle.{Server => FinagleServer, Thrift => FinagleThrift, _}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.thrift.ThriftClientRequest
import io.buoyant.router.thrift.{Identifier, TracingFilter}
import java.net.SocketAddress

object Thrift extends Router[ThriftClientRequest, Array[Byte]]
  with FinagleServer[Array[Byte], Array[Byte]] {

  object param {
    case class MethodInDst(enabled: Boolean)
    implicit object MethodInDst extends Stack.Param[MethodInDst] {
      val default = MethodInDst(false)
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
        .transformed(_.replace(TracingFilter.role, TracingFilter.module))

    val defaultParams: Stack.Params =
      StackRouter.defaultParams +
        ProtocolLibrary("thrift")

    /**
     * Wraps a byte-array payload as a two-way ThriftClientRequest.
     */
    object IngestingFilter extends Filter[Array[Byte], Array[Byte], ThriftClientRequest, Array[Byte]] {
      def apply(bytes: Array[Byte], svc: Service[ThriftClientRequest, Array[Byte]]) =
        svc(new ThriftClientRequest(bytes, false))
    }
  }

  case class Router(
    pathStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] = Router.pathStack,
    boundStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] = Router.boundStack,
    client: StackClient[ThriftClientRequest, Array[Byte]] = Router.client,
    params: Stack.Params = Router.defaultParams
  ) extends StdStackRouter[ThriftClientRequest, Array[Byte], Router] {

    protected def copy1(
      pathStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] = this.pathStack,
      boundStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] = this.boundStack,
      client: StackClient[ThriftClientRequest, Array[Byte]] = this.client,
      params: Stack.Params = this.params
    ): Router = copy(pathStack, boundStack, client, params)

    protected def newIdentifier(): RoutingFactory.Identifier[ThriftClientRequest] = {
      val RoutingFactory.DstPrefix(pfx) = params[RoutingFactory.DstPrefix]
      val param.MethodInDst(methodInDst) = params[param.MethodInDst]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]
      val FinagleThrift.param.ProtocolFactory(protocol) = params[FinagleThrift.param.ProtocolFactory]
      Identifier(pfx, methodInDst, baseDtab, protocol)
    }
  }

  val router = Router()
  def factory(): ServiceFactory[ThriftClientRequest, Array[Byte]] =
    router.factory()

  val server = FinagleThrift.Server()

  def serve(
    addr: SocketAddress,
    factory: ServiceFactory[Array[Byte], Array[Byte]]
  ): ListeningServer =
    server.serve(addr, factory)
}
