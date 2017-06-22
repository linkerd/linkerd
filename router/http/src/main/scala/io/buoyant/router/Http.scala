package io.buoyant.router

import com.twitter.finagle.client.StackClient
import com.twitter.finagle.http.{Request, Response, TlsFilter}
import com.twitter.finagle.http.service.HttpResponseClassifier
import com.twitter.finagle.param.{ProtocolLibrary, ResponseClassifier}
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.{Http => FinagleHttp, Server => FinagleServer, http => fhttp, _}
import io.buoyant.router.ClassifiedRetries.ResponseDiscarder
import io.buoyant.router.Http.param.HttpIdentifier
import io.buoyant.router.http._
import java.net.SocketAddress

object Http extends Router[Request, Response] with FinagleServer[Request, Response] {

  object param {
    case class HttpIdentifier(id: (Path, () => Dtab) => RoutingFactory.Identifier[fhttp.Request])
    implicit object HttpIdentifier extends Stack.Param[HttpIdentifier] {
      val default = HttpIdentifier(HeaderIdentifier.default)
    }
  }

  object Router {
    val pathStack: Stack[ServiceFactory[Request, Response]] =
      ViaHeaderAppenderFilter.module +:
        StripHopByHopHeadersFilter.module +:
        ClassifierFilter.module +:
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

    val responseDiscarder = ResponseDiscarder[Response] { rsp =>
      if (rsp.isChunked) {
        rsp.reader.discard()
      }
    }
    implicit val discarderParam = ResponseDiscarder.param[Response]

    val defaultParams: Stack.Params =
      StackRouter.defaultParams +
        fhttp.param.Streaming(true) +
        fhttp.param.Decompression(false) +
        responseDiscarder +
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
      (AddForwardedHeader.module +: FinagleHttp.server.stack)
        .insertBefore(http.TracingFilter.role, ProxyRewriteFilter.module)

    private val serverResponseClassifier = ClassifiedRetries.orElse(
      ClassifierFilter.successClassClassifier,
      HttpResponseClassifier.ServerErrorsAsFailures
    )

    val defaultParams: Stack.Params =
      StackServer.defaultParams +
        ResponseClassifier(serverResponseClassifier) +
        fhttp.param.Streaming(true) +
        fhttp.param.Decompression(false) +
        ProtocolLibrary("http")
  }

  val server = FinagleHttp.Server(Server.stack, Server.defaultParams)

  def serve(addr: SocketAddress, factory: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, factory)
}
