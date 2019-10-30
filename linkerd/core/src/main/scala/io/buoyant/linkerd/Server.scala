package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.concurrent.AsyncSemaphore
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.buoyant.{ParamsMaybeWith, SocketOptionsConfig, TlsServerConfig}
import com.twitter.finagle.filter.RequestSemaphoreFilter
import com.twitter.finagle.netty4.ssl.server.Netty4ServerEngineFactory
import com.twitter.finagle.service.{ExpiringService, TimeoutFilter}
import com.twitter.finagle.ssl.server.SslServerEngineFactory
import com.twitter.finagle.{ListeningServer, Path, Stack}
import io.buoyant.config.types.Port
import java.net.{InetAddress, InetSocketAddress}

/**
 * A Server configuration, describing a request-receiving interface
 * for a [[Router]].
 *
 * Concrete implementations are provided by [[ProtocolInitializer]].
 */
trait Server {
  def protocol: ProtocolInitializer

  def params: Stack.Params
  def configured[T: Stack.Param](t: T): Server
  def withParams(ps: Stack.Params): Server

  def router: String
  def label: String
  def ip: InetAddress

  def port: Int

  def addr: InetSocketAddress = new InetSocketAddress(ip, port)

  def announce: Seq[Path]
}

object Server {
  /**
   * A [[Server]] that is fully configured but not yet listening.
   */
  trait Initializer {
    def protocol: ProtocolInitializer

    def params: Stack.Params

    def router: String

    def ip: InetAddress

    def port: Int

    def addr: InetSocketAddress

    def serve(): ListeningServer

    def announce: Seq[Path]
  }

  case class Impl(
    router: String,
    ip: InetAddress,
    label: String,
    port: Int,
    protocol: ProtocolInitializer,
    params: Stack.Params,
    announce: Seq[Path]
  ) extends Server {
    override def configured[T: Stack.Param](t: T): Server = copy(params = params + t)

    override def withParams(ps: Stack.Params): Server = copy(params = ps)
  }
}

class ServerConfig { config =>

  var port: Option[Port] = None
  var socketOptions: Option[SocketOptionsConfig] = None
  var ip: Option[InetAddress] = None
  var tls: Option[TlsServerConfig] = None
  var label: Option[String] = None
  var maxConcurrentRequests: Option[Int] = None
  var announce: Option[Seq[String]] = None
  var timeoutMs: Option[Int] = None
  var serverSession: Option[ServerSessionConfig] = None
  var clearContext: Option[Boolean] = None

  private[this] def requestSemaphore =
    maxConcurrentRequests.map(new AsyncSemaphore(_, 0))

  @JsonIgnore
  protected def serverParams: Stack.Params = Stack.Params.empty
    .maybeWith(socketOptions.map(_.params))
    .maybeWith(tls.map(_.params(alpnProtocols, sslServerEngine)))
    .maybeWith(clearContext.map(ClearContext.Enabled(_)))
    .maybeWith(timeoutMs.map(timeout => TimeoutFilter.Param(timeout.millis)))
    .maybeWith(serverSession.map(_.param)) +
    RequestSemaphoreFilter.Param(requestSemaphore)

  @JsonIgnore
  def alpnProtocols: Option[Seq[String]] = None

  @JsonIgnore
  val sslServerEngine: SslServerEngineFactory = Netty4ServerEngineFactory()

  @JsonIgnore
  def mk(pi: ProtocolInitializer, routerLabel: String) = Server.Impl(
    routerLabel,
    ip.getOrElse(InetAddress.getLoopbackAddress),
    label.getOrElse(routerLabel),
    port.map(_.port).getOrElse(pi.defaultServerPort),
    pi,
    serverParams,
    announce.toSeq.flatten.map(Path.read)
  )
}

case class ServerSessionConfig(
  @JsonDeserialize(contentAs = classOf[java.lang.Integer]) lifeTimeMs: Option[Int],
  @JsonDeserialize(contentAs = classOf[java.lang.Integer]) idleTimeMs: Option[Int]
) extends SessionConfig
