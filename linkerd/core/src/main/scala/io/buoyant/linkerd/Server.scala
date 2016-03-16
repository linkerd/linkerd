package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.concurrent.AsyncSemaphore
import com.twitter.finagle.Stack.{Param, Params}
import com.twitter.finagle.filter.RequestSemaphoreFilter
import com.twitter.finagle.ssl.Ssl
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{ListeningServer, Stack}
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
}

object Server {

  case class RouterLabel(label: String)

  implicit object RouterLabel extends Stack.Param[RouterLabel] {
    val default = RouterLabel("")
  }

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
  }

  case class Impl(
    router: String,
    ip: InetAddress,
    label: String,
    port: Int,
    protocol: ProtocolInitializer,
    params: Stack.Params
  ) extends Server {
    override def configured[T: Param](t: T): Server = copy(params = params + t)

    override def withParams(ps: Params): Server = copy(params = ps)
  }
}

class ServerConfig { config =>

  var port: Option[Port] = None
  var ip: Option[InetAddress] = None
  var tls: Option[TlsServerConfig] = None
  var label: Option[String] = None
  var maxConcurrentRequests: Option[Int] = None

  private[this] def requestSemaphore = maxConcurrentRequests.map(new AsyncSemaphore(_, 0))

  @JsonIgnore
  protected def serverParams: Stack.Params = Stack.Params.empty
    .maybeWith(tls.map {
      case TlsServerConfig(certPath, keyPath) => tlsParam(certPath, keyPath)
    }) + RequestSemaphoreFilter.Param(requestSemaphore)

  @JsonIgnore
  private[this] def tlsParam(certificatePath: String, keyPath: String) =
    Transport.TLSServerEngine(
      Some(() => Ssl.server(certificatePath, keyPath, null, null, null))
    )

  @JsonIgnore
  def mk(pi: ProtocolInitializer, routerLabel: String) = Server.Impl(
    routerLabel,
    ip.getOrElse(InetAddress.getLoopbackAddress),
    label.getOrElse(routerLabel),
    port.map(_.port).getOrElse(pi.defaultServerPort),
    pi,
    serverParams
  )
}

case class TlsServerConfig(certPath: String, keyPath: String)
