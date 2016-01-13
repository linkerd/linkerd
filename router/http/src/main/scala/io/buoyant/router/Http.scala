package io.buoyant.router

import com.twitter.finagle.{Http => FinagleHttp, Server => FinagleServer, http => _, _}
import com.twitter.finagle.buoyant._
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.server.StackServer
import io.buoyant.router.http.{AccessLogger, ErrorResponder, ForwardedFilter, Headers, Identifier}
import java.net.SocketAddress

object Http extends Router[Request, Response] with FinagleServer[Request, Response] {

  object param {

    /** Whether URI paths should be included in Http router destinations. */
    case class UriInDst(enabled: Boolean)
    implicit object UriInDst extends Stack.Param[UriInDst] {
      val default = UriInDst(enabled = false)
    }
  }

  object Router {
    val pathStack: Stack[ServiceFactory[Request, Response]] =
      Headers.PathFilter +: StackRouter.newPathStack[Request, Response]

    val boundStack: Stack[ServiceFactory[Request, Response]] =
      Headers.BoundFilter +: StackRouter.newBoundStack[Request, Response]

    val client: StackClient[Request, Response] = {
      val builder = new StackBuilder(FinagleHttp.Client.stack)
      builder.push(AccessLogger.module)
      builder.push(HttpStatusCodeFilter.module)
      val stk = StackRouter.Client.mkStack(builder.result)
        .replace(HttpTraceInitializer.role, HttpTraceInitializer.client)
        .replace(StackClient.Role.protoTracing, http.TracingFilter)
      FinagleHttp.client.withStack(stk)
    }

    val defaultParams: Stack.Params =
      StackRouter.defaultParams +
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
      val param.UriInDst(uriInDst) = params[param.UriInDst]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]
      Identifier(pfx, uriInDst, baseDtab)
    }
  }

  val router = Router()
  def factory(): ServiceFactory[Request, Response] =
    router.factory()

  object Server {
    val stack: Stack[ServiceFactory[Request, Response]] = {
      val stk = new StackBuilder(FinagleHttp.Server.stack)
      stk.push(ForwardedFilter.module)
      stk.push(ErrorResponder)
      stk.result.replace(HttpTraceInitializer.role, HttpTraceInitializer.server)
    }

    val defaultParams: Stack.Params =
      StackServer.defaultParams +
        FinagleHttp.param.Streaming(true) +
        ProtocolLibrary("http")
  }

  val server = FinagleHttp.Server(Server.stack, Server.defaultParams)

  def serve(addr: SocketAddress, factory: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, factory)
}
