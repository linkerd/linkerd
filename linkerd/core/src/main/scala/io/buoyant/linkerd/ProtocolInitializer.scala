package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.param.Label
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.stack.nilStack
import com.twitter.util.{Future, Time}
import io.buoyant.config.ConfigInitializer
import io.buoyant.router._
import java.net.InetSocketAddress

/**
 * Provides a protocol-agnostic interface for protocol-specific
 * configuration & initialization.  Adapts linkerd's [[Router]] with
 * `io.buoyant.router.Router` and [[Server]] to
 * `com.twitter.finagle.Server`.
 *
 * Furthermore a protocol may provide parsers for protocol-specific
 * configuration parameters.
 *
 */
abstract class ProtocolInitializer extends ConfigInitializer { initializer =>
  import ProtocolInitializer._

  /** The protocol name, as read from configuration. */
  def name: String
  override def configId = name

  /*
   * Router configuration & initialization
   */
  protected type RouterReq
  protected type RouterRsp

  /** The default protocol-specific router configuration */
  protected def defaultRouter: StackRouter[RouterReq, RouterRsp]

  def experimentalRequired: Boolean = false

  protected def configureServer(router: Router, server: Server): Server = {
    val ip = server.ip.getHostAddress
    val port = server.port
    val param.Stats(stats) = router.params[param.Stats]
    val routerLabel = router.label
    server.configured(param.Label(s"$ip/$port"))
      .configured(RouterLabel.Param(routerLabel))
      .configured(param.Stats(stats.scope("server")))
      .configured(router.params[param.Tracer])
  }

  /**
   * Satisfies the protocol-agnostic linkerd Router interface by
   * wrapping the protocol-specific router stack.
   */
  private case class ProtocolRouter(
    router: StackRouter[RouterReq, RouterRsp],
    servers: Seq[Server] = Nil,
    announcers: Seq[(Path, Announcer)] = Nil
  ) extends Router {
    def params = router.params
    def protocol = ProtocolInitializer.this

    protected def _withParams(ps: Stack.Params): Router =
      copy(router = router.withParams(ps))

    protected def configureServer(s: Server): Server =
      initializer.configureServer(this, s)

    protected def withServers(ss: Seq[Server]): Router = copy(servers = ss)

    def withAnnouncers(ann: Seq[(Path, Announcer)]): Router = copy(announcers = ann)

    def initialize(): Router.Initialized = {
      if (servers.isEmpty) {
        val Label(name) = params[Label]
        throw new IllegalStateException(s"router '$name' has no servers")
      }

      val pathStk = router.pathStack.prepend(MetricsPruningModule.module[RouterReq, RouterRsp])
      val clientStk = router.clientStack.prepend(MetricsPruningModule.module[RouterReq, RouterRsp])

      val factory = router
        .withPathStack(pathStk)
        .withClientStack(clientStk)
        .factory()

      // Don't let server closure close the router.
      val adapted = new ServiceFactoryProxy(adapter.andThen(factory)) {
        override def close(d: Time) = Future.Unit
      }

      val servable = servers.map { s =>
        val stk = s.params[ClearContext.Enabled] match {
          case ClearContext.Enabled(true) => clearServerContext(defaultServer.stack)
          case ClearContext.Enabled(false) => defaultServer.stack
        }

        val stacked = defaultServer
          .withStack(stk)
          .withParams(defaultServer.params ++ s.params)
        ServerInitializer(protocol, s.addr, stacked, adapted, s.announce)
      }
      InitializedRouter(protocol, params, factory, servable, announcers)
    }
  }

  def router: Router = ProtocolRouter(defaultRouter)
    .configured(Label(name))

  /*
   * Server initialization
   */
  protected type ServerReq
  protected type ServerRsp
  protected type ServerStack = Stack[ServiceFactory[ServerReq, ServerRsp]]

  /** Adapts a server to a router */
  protected def adapter: Filter[ServerReq, ServerRsp, RouterReq, RouterRsp]

  /** The default protocol-specific server configuration */
  protected def defaultServer: StackServer[ServerReq, ServerRsp]

  protected def clearServerContext(stk: ServerStack): ServerStack =
    stk ++ (ClearContext.module[ServerReq, ServerRsp] +: nilStack)

  def defaultServerPort: Int
}

object ProtocolInitializer {

  /**
   * A [[ProtocolInitializer]] whose Server and Router have identical
   * request and response types.
   */
  trait Simple extends ProtocolInitializer {
    protected type Req
    protected type Rsp
    protected final type RouterReq = Req
    protected final type RouterRsp = Rsp
    protected final type ServerReq = Req
    protected final type ServerRsp = Rsp
    protected val adapter = Filter.identity[Req, Rsp]
  }

  /** Protocol-aware implementation of [[Router.Initialized]]. */
  private case class InitializedRouter[Req, Rsp](
    protocol: ProtocolInitializer,
    params: Stack.Params,
    factory: ServiceFactory[Req, Rsp],
    servers: Seq[Server.Initializer],
    announcers: Seq[(Path, Announcer)]
  ) extends Router.Initialized {
    def name: String = params[Label].label
    def close(t: Time) = factory.close(t)
  }

  /** Protocol-aware implementation of [[Server.Initializer]]. */
  private case class ServerInitializer[Req, Rsp](
    protocol: ProtocolInitializer,
    addr: InetSocketAddress,
    server: StackServer[Req, Rsp],
    factory: ServiceFactory[Req, Rsp],
    announce: Seq[Path]
  ) extends Server.Initializer {
    def params = server.params
    def router: String = server.params[RouterLabel.Param].label
    def ip = addr.getAddress
    def port = addr.getPort
    def serve() = server.serve(addr, factory)
  }
}
