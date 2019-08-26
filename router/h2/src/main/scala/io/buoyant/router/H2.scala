package io.buoyant.router

import com.twitter.finagle.buoyant.h2.{H2FailureAccrualFactory, Headers, Request, Response, param => h2param, Stream}
import com.twitter.finagle.buoyant.{H2 => FinagleH2}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.{param, _}
import com.twitter.finagle.server.StackServer
import com.twitter.util.Future
import java.net.SocketAddress
import com.twitter.finagle.liveness.FailureAccrualFactory
import com.twitter.finagle.service.StatsFilter
import io.buoyant.router.context.ResponseClassifierCtx
import io.buoyant.router.context.h2.H2ClassifierCtx
import io.buoyant.router.h2.{ClassifiedRetries => H2ClassifiedRetries, _}
import io.buoyant.router.http.{ForwardClientCertFilter, MaxCallDepthFilter}
import io.buoyant.router.H2Instances._
import io.buoyant.router.DiscardingFactoryToService.RequestDiscarder

object H2 extends Router[Request, Response]
  with Client[Request, Response]
  with Server[Request, Response] {

  /*
   * Router
   */

  case class Identifier(mk: Stack.Params => RoutingFactory.Identifier[Request])
  implicit private[buoyant] object Identifier extends Stack.Param[Identifier] {
    private[this] val nilF =
      Future.value(new RoutingFactory.UnidentifiedRequest[Request]("no request identifier"))
    private[this] val nil = (_req: Request) => nilF
    val default = Identifier(params => nil)
  }

  object Router {

    val requestDiscarder = RequestDiscarder[Request](x => {
      Stream.readToEnd(x.stream)
      ()
    })
    implicit val discarderParam = RequestDiscarder.param[Request]

    val pathStack: Stack[ServiceFactory[Request, Response]] = {
      val stk = h2.ViaHeaderFilter.module +: h2.ClassifierFilter.module +:
        StackRouter.newPathStack[Request, Response]
      stk.replace(
        ResponseClassifierCtx.Setter.role,
        H2ClassifierCtx.Setter.module[Request, Response]
      ).replace(ClassifiedRetries.role, H2ClassifiedRetries.module)
        .replace(StatsFilter.role, StreamStatsFilter.module)
    }

    val boundStack: Stack[ServiceFactory[Request, Response]] =
      StackRouter.newBoundStack

    val clientStack: Stack[ServiceFactory[Request, Response]] = {
      val stk = FinagleH2.Client.newStack
      (ForwardClientCertFilter.module[Request, Headers, Response] +: StackRouter.Client.mkStack(stk))
        .replace(PerDstPathStatsFilter.role, PerDstPathStreamStatsFilter.module)
        .replace(LocalClassifierStatsFilter.role, LocalClassifierStreamStatsFilter.module)
        .replace(FailureAccrualFactory.role, H2FailureAccrualFactory.module)
    }

    val defaultParams = StackRouter.defaultParams +
      param.ProtocolLibrary("h2") + requestDiscarder
  }

  case class Router(
    pathStack: Stack[ServiceFactory[Request, Response]] = Router.pathStack,
    boundStack: Stack[ServiceFactory[Request, Response]] = Router.boundStack,
    client: StackClient[Request, Response] = FinagleH2.Client(Router.clientStack),
    params: Stack.Params = Router.defaultParams
  ) extends StdStackRouter[Request, Response, Router] {

    protected def copy1(
      pathStack: Stack[ServiceFactory[Request, Response]] = this.pathStack,
      boundStack: Stack[ServiceFactory[Request, Response]] = this.boundStack,
      client: StackClient[Request, Response] = this.client,
      params: Stack.Params = this.params
    ): Router = copy(pathStack, boundStack, client, params)

    protected def newIdentifier() = params[Identifier].mk(params)
  }

  val router = Router()

  def factory(): ServiceFactory[Request, Response] =
    router.factory()

  val client = FinagleH2.client

  def newService(dest: Name, label: String): Service[Request, Response] =
    client.newService(dest, label)

  def newClient(dest: Name, label: String): ServiceFactory[Request, Response] =
    client.newClient(dest, label)

  object Server {
    val newStack: Stack[ServiceFactory[Request, Response]] = FinagleH2.Server.newStack
      .insertAfter(StackServer.Role.protoTracing, h2.ProxyRewriteFilter.module)
      .insertAfter(StackServer.Role.protoTracing, H2AddForwardedHeader.module)
      .insertAfter(StackServer.Role.protoTracing, MaxCallDepthFilter.module[Request, Headers, Response](Headers.Via))
      .replace(StatsFilter.role, StreamStatsFilter.module)

    private val serverResponseClassifier =
      ClassifierFilter.SuccessClassClassifier

    val defaultParams: Stack.Params =
      StackServer.defaultParams + h2param.H2Classifier(serverResponseClassifier)
  }

  val server = FinagleH2.Server(Server.newStack, Server.defaultParams)

  def serve(addr: SocketAddress, service: ServiceFactory[Request, Response]): ListeningServer =
    server.serve(addr, service)

}
