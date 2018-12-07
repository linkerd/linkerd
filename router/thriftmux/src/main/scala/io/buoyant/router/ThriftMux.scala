package io.buoyant.router

import java.net.SocketAddress

import com.twitter.finagle.{Filter, ListeningServer, Service, ServiceFactory, Stack, mux}
import com.twitter.finagle.{Server => FinagleServer, Thrift => FinagleThrift, ThriftMux => FinagleThriftMux}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.thrift.ThriftClientRequest
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.router.thrift.{Identifier => ThriftIdentifier}

object ThriftMux extends Router[ThriftClientRequest, Array[Byte]] with FinagleServer[mux.Request, mux.Response] {
  object Router {
    val pathStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] =
      StackRouter.newPathStack[ThriftClientRequest, Array[Byte]]

    val boundStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] =
      StackRouter.newBoundStack[ThriftClientRequest, Array[Byte]]

    val client: StackClient[ThriftClientRequest, Array[Byte]] =
      FinagleThrift.client.withStack(StackRouter.Client.mkStack(_))

    val defaultParams: Stack.Params =
      StackRouter.defaultParams + ProtocolLibrary("thriftmux")

    object IngestingFilter extends Filter[mux.Request, mux.Response, ThriftClientRequest, Array[Byte]] {
      def apply(request: mux.Request, service: Service[ThriftClientRequest, Array[Byte]]): Future[mux.Response] = {
        val reqBytes = Buf.ByteArray.Owned.extract(request.body)
        service(new ThriftClientRequest(reqBytes, false)) map { repBytes =>
          mux.Response(Buf.ByteArray.Owned(repBytes))
        }
      }
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
      val Thrift.param.MethodInDst(methodInDst) = params[Thrift.param.MethodInDst]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]
      ThriftIdentifier(pfx, methodInDst, baseDtab)
    }
  }

  val router = Router()
  def factory(): ServiceFactory[ThriftClientRequest, Array[Byte]] = router.factory()

  val server = FinagleThriftMux.Server().muxer
  def serve(addr: SocketAddress, service: ServiceFactory[mux.Request, mux.Response]): ListeningServer =
    server.serve(addr, service)
}
