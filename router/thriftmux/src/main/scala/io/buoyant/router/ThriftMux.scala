package io.buoyant.router

import java.net.SocketAddress

import com.twitter.finagle.Thrift.param.ProtocolFactory
import com.twitter.finagle.ThriftMux.ServerMuxer
import com.twitter.finagle.{ListeningServer, ServiceFactory, Stack, Server => FinagleServer, Thrift => FinagleThrift, ThriftMux => FinagleThriftMux}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.mux.{Request, Response}
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.thrift.ThriftClientRequest
import io.buoyant.router.thrift.{Identifier => ThriftIdentifier}

object ThriftMux extends Router[ThriftClientRequest, Array[Byte]] with FinagleServer[Array[Byte], Array[Byte]] {
  object Router {
    val pathStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] =
      StackRouter.newPathStack[ThriftClientRequest, Array[Byte]]

    val boundStack: Stack[ServiceFactory[ThriftClientRequest, Array[Byte]]] =
      StackRouter.newBoundStack[ThriftClientRequest, Array[Byte]]

    val client: StackClient[ThriftClientRequest, Array[Byte]] =
      FinagleThrift.client.transformed(StackRouter.Client.mkStack(_))

    val defaultParams: Stack.Params =
      StackRouter.defaultParams + ProtocolLibrary("thriftmux")
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

  val server = FinagleThriftMux.Server()
  def server(muxer: StackServer[Request, Response] = ServerMuxer()) = FinagleThriftMux.Server(muxer)
  def serve(addr: SocketAddress, factory: ServiceFactory[Array[Byte], Array[Byte]]): ListeningServer =
    server.serve(addr, factory)
}