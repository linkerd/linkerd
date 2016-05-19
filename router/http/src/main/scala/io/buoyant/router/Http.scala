package io.buoyant.router

import com.twitter.finagle.{Http => FinagleHttp, Server => FinagleServer, http => fhttp, _}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.http.{Request, Response, TlsFilter}
import com.twitter.finagle.netty4.http.exp.Netty4Impl
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.server.StackServer
import io.buoyant.router.Http.param.HttpIdentifier
import io.buoyant.router.http.{MethodAndHostIdentifier, ForwardedFilter, StripConnectionHeader}
import java.net.SocketAddress

object Http extends Router[Request, Response] with FinagleServer[Request, Response] {

  object param {
    case class HttpIdentifier(id: (Path, () => Dtab) => RoutingFactory.Identifier[fhttp.Request])
    implicit object HttpIdentifier extends Stack.Param[HttpIdentifier] {
      val default = HttpIdentifier(MethodAndHostIdentifier.mk)
    }
  }

  object Router {
    val pathStack: Stack[ServiceFactory[Request, Response]] =
      StripConnectionHeader.module +:
        StackRouter.newPathStack[Request, Response]

    val boundStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.newBoundStack[Request, Response]

    /**
     * Install better http tracing and prevent TLS/Host-header interference.
     */
    val client: StackClient[Request, Response] = FinagleHttp.client
      .transformed(StackRouter.Client.mkStack(_))
      .transformed(_.replace(http.TracingFilter.role, http.TracingFilter.module))
      .transformed(_.remove(TlsFilter.role))

    val defaultParams: Stack.Params =
      StackRouter.defaultParams +
        Netty4Impl +
        FinagleHttp.param.Streaming(true) +
        ProtocolLibrary("http")
  }

  case class Router(
    pathStack: Stack[ServiceFactory[Request, Response]] = Router.pathStack,
    boundStack: Stack[ServiceFactory[Request, Response]] = Router.boundStack,
    client: StackClient[Request, Response] = Router.client,
    params: Stack.Params = Router.defaultParams
  ) extends StdStackRouter[Request, Response, Router] {
    protected def copy1(
      pathStack: Stack[ServiceFactory[Request, Response]] = this.pathStack,
      boundStack: Stack[ServiceFactory[Request, Response]] = this.boundStack,
      client: StackClient[Request, Response] = this.client,
      params: Stack.Params = this.params
    ): Router = copy(pathStack, boundStack, client, params)

    protected def newIdentifier(): RoutingFactory.Identifier[Request] = {
      val RoutingFactory.DstPrefix(pfx) = params[RoutingFactory.DstPrefix]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]
      val param.HttpIdentifier(id) = params[param.HttpIdentifier]
      id(pfx, baseDtab)
    }
  }

  val router = Router()
  def factory(): ServiceFactory[Request, Response] =
    router.factory()

  object Server {
    val stack: Stack[ServiceFactory[Request, Response]] =
      (ForwardedFilter.module +: FinagleHttp.Server.stack)

    val defaultParams: Stack.Params =
      StackServer.defaultParams +
        Netty4Impl +
        FinagleHttp.param.Streaming(true) +
        ProtocolLibrary("http")
  }

  val server = FinagleHttp.Server(Server.stack, Server.defaultParams)

  def serve(addr: SocketAddress, factory: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, factory)
}
