package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.ghik.silencer.silent
import com.twitter.concurrent.AsyncSemaphore
import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.{TlsServerConfig, ParamsMaybeWith}
import com.twitter.finagle.filter.RequestSemaphoreFilter
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle.ssl.server.{LegacyKeyServerEngineFactory, SslServerEngineFactory}
import com.twitter.finagle.{ListeningServer, Path, Stack}
import com.twitter.finagle.buoyant.ParamsMaybeWith
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
  var ip: Option[InetAddress] = None
  var tls: Option[TlsServerConfig] = None
  var label: Option[String] = None
  var maxConcurrentRequests: Option[Int] = None
  var announce: Option[Seq[String]] = None
  var timeoutMs: Option[Int] = None

  var clearContext: Option[Boolean] = None

  private[this] def requestSemaphore =
    maxConcurrentRequests.map(new AsyncSemaphore(_, 0))

  @JsonIgnore
  protected def serverParams: Stack.Params = Stack.Params.empty
    .maybeWith(tls.map(_.params(alpnProtocols, sslServerEngine)))
    .maybeWith(clearContext.map(ClearContext.Enabled(_)))
    .maybeWith(timeoutMs.map(timeout => TimeoutFilter.Param(timeout.millis))) +
    RequestSemaphoreFilter.Param(requestSemaphore)

  @JsonIgnore
  def alpnProtocols: Option[Seq[String]] = None

  // The deprecated LegacyKeyServerEngineFactory allows us to accept PKCS#1 formatted keys.
  // We should remove this and replace it with Netty4ServerEngineFactory once we no longer allow
  // PKCS#1 keys.
  @JsonIgnore
  @silent
  val sslServerEngine: SslServerEngineFactory = LegacyKeyServerEngineFactory

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
