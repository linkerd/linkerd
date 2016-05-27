package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.param.Label
import com.twitter.finagle.server.StackServer
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
trait ProtocolInitializer extends ConfigInitializer {
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

  /**
   * Satisfies the protocol-agnostic linkerd Router interface by
   * wrapping the protocol-specific router stack.
   */
  private case class ProtocolRouter(
    router: StackRouter[RouterReq, RouterRsp],
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

      val factory = router.factory()
      val adapted = adapter.andThen(factory)
      val servable = servers.map { server =>
        val stackServer = defaultServer.withParams(server.params)
        ServerInitializer(protocol, server.addr, stackServer, adapted)
      }
      InitializedRouter(protocol, params, factory, servable)
    }

    private[this] val tlsPrepRole = Stack.Role("TlsClientPrep")

    def withTls(tls: TlsClientConfig): Router = {
      val tlsPrep = tls.tlsClientPrep[RouterReq, RouterRsp]
      val clientStack = router.clientStack.replace(tlsPrepRole, tlsPrep)
      copy(router = router.withClientStack(clientStack))
    }
  }

  def router: Router = ProtocolRouter(defaultRouter)
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


