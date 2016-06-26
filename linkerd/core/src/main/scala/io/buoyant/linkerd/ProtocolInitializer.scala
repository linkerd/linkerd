package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.param.Label
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.util.Time
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
abstract class ProtocolInitializer extends ConfigInitializer {
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

  private[this] val timeoutModule =
    Router.ClientTimeout.module[ServiceFactory[RouterReq, RouterRsp]]

  /**
   * Satisfies the protocol-agnostic linkerd Router interface by
   * wrapping the protocol-specific router stack.
   */
  private case class ProtocolRouter(
    router: StackRouter[RouterReq, RouterRsp] = defaultRouter,
    servers: Seq[Server] = Nil
  ) extends Router {
    def params = router.params
    def protocol = ProtocolInitializer.this

    protected def _withParams(ps: Stack.Params): Router =
      copy(router = router.withParams(ps))

    protected def withServers(ss: Seq[Server]): Router = copy(servers = ss)

    def initialize(): Router.Initialized = {
      if (servers.isEmpty) {
        val Label(name) = params[Label]
        throw new IllegalStateException(s"router '$name' has no servers")
      }

      val factory = router
        .withClientStack(router.clientStack
          .insertBefore(TimeoutFilter.role, timeoutModule))
        .withParams(router.params)
        .factory()

      val servable = {
        val adapted = adapter.andThen(factory)

        servers.map { server =>
          val stackServer = defaultServer.withParams(Router.serverParams(this, server))
          ServerInitializer(protocol, server.addr, stackServer, adapted)
        }
      }

      InitializedRouter(protocol, params, factory, servable)
    }

    def withTls(tls: TlsClientConfig): Router = {
      val clientStack = router.clientStack
        .replace(TlsClientPrep.role, tls.tlsClientPrep[RouterReq, RouterRsp])
      copy(router = router.withClientStack(clientStack))
    }
  }

  def router: Router = ProtocolRouter()
    .configured(Label(name))

  /*
   * Server initialization
   */
  protected type ServerReq
  protected type ServerRsp

  /** Adapts a server to a router */
  protected def adapter: Filter[ServerReq, ServerRsp, RouterReq, RouterRsp]

  /** The default protocol-specific server configuration */
  protected def defaultServer: StackServer[ServerReq, ServerRsp]

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
    servers: Seq[Server.Initializer]
  ) extends Router.Initialized {
    def name: String = params[Label].label
    def close(t: Time) = factory.close(t)
  }

  /** Protocol-aware implementation of [[Server.Initializer]]. */
  private case class ServerInitializer[Req, Rsp](
    protocol: ProtocolInitializer,
    addr: InetSocketAddress,
    server: StackServer[Req, Rsp],
    factory: ServiceFactory[Req, Rsp]
  ) extends Server.Initializer {
    def params = server.params
    def router: String = server.params[Server.RouterLabel].label
    def ip = addr.getAddress
    def port = addr.getPort
    def serve() = server.serve(addr, factory)
  }
}
