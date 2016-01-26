package io.buoyant.linkerd

import com.fasterxml.jackson.core.{io => _, _}
import com.google.common.net.InetAddresses
import com.twitter.finagle.ssl.Ssl
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{ListeningServer, Stack}
import com.twitter.finagle.param.Label
import java.net.{InetAddress, InetSocketAddress}
import scala.annotation.tailrec

/**
 * A Server configuration, describing a request-receiving interface
 * for a [[Router]].
 *
 * Concrete implementations are provided by [[ProtocolInitializer]].
 */
trait Server {
  def protocol: ProtocolInitializer

  def params: Stack.Params
  def withParams(ps: Stack.Params): Server
  def configured[P: Stack.Param](p: P): Server = withParams(params + p)
  def configured(ps: Stack.Params): Server = withParams(params ++ ps)

  /**
   * Read a [[Server]] configuration in the form:
   *
   * <pre>
   *     port: 1234
   *     ip: 0.0.0.0
   *     protoParam: yes
   * </pre>
   *
   * If a [[Server.Port Port]] is not specified, then a random port is
   * allocated.
   *
   * If `ip` is not specified, the port is bound on the loopback
   * address (e.g. 127.0.0.1). It would be reckless to default to open
   * arbitrary relays on the potentially-public internet, so users
   * must specify i.e. `0.0.0.0` to cause the server to bind on all
   * local interfaces.
   *
   * Additional fields are treated as server parameters.  Currently,
   * only protocol-specific server parameters are supported.
   */
  def configuredFrom(json: JsonParser): Server

  def router: String = params[Server.RouterLabel].label
  def label: String = params[Label].label
  def ip: InetAddress = params[Server.Ip].ip

  def port: Int = params[Server.Port] match {
    case Server.Port.Unknown => 0
    case Server.Port.Specified(p) => p
  }

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

  /**
   * `Ip` server parameter.
   */
  case class Ip(ip: InetAddress)

  implicit object Ip extends Stack.Param[Ip] {
    val loopback = Ip(InetAddress.getLoopbackAddress)
    val any = Ip(InetAddress.getByAddress(Array(0, 0, 0, 0)))
    val default = loopback

    def mk(ip: String): Ip = ip match {
      case "" => default
      case ip => Ip(InetAddresses.forString(ip))
    }
  }

  /**
   * `Port` server parameter.
   */
  sealed trait Port
  implicit object Port extends Stack.Param[Port] {
    val MinValue = 0
    val MaxValue = math.pow(2, 16) - 1
    def isValid(n: Int): Boolean = MinValue <= n && n <= MaxValue

    object Unknown extends Port
    case class Specified(port: Int) extends Port {
      require(isValid(port))
    }

    def apply(p: Int): Port = Specified(p)

    val default: Port = Unknown

    def read(json: JsonParser): Port =
      Parsing.ensureTok(json, JsonToken.VALUE_NUMBER_INT) { json =>
        json.getValueAsInt match {
          case p if isValid(p) => Specified(p)
          case p => throw Parsing.error(s"Invalid port value: $p", json)
        }
      }
  }

  def conflicts(addr0: InetSocketAddress, addr1: InetSocketAddress): Boolean =
    (addr0.getPort == addr1.getPort) && {
      val (a0, a1) = (addr0.getAddress, addr1.getAddress)
      a0.isAnyLocalAddress || a1.isAnyLocalAddress || a0 == a1
    }

  def conflicts(s0: Server, s1: Server): Boolean =
    conflicts(s0.addr, s1.addr)

  /** Find a server that is configured to bind on overlapping socket. */
  @tailrec def findConflict(server: Server, others: Seq[Server]): Option[Server] =
    others match {
      case Nil => None
      case Seq(other, _*) if conflicts(server, other) => Some(server)
      case Seq(_, others@_*) => findConflict(server, others)
    }

  /** Find a pair of servers that are configured on overlapping sockets. */
  def findConflict(servers: Seq[Server]): Option[(Server, Server)] = {
    // Fold over either:
    // - Left: the accumulated non-conflicting list of servers
    // - Right: a pair of conflict servers
    // We start with an empty accumulated set:
    val init: Either[Seq[Server], (Server, Server)] = Left(Nil)
    val result = servers.foldLeft(init) {
      case (conflict@Right(_), _) => conflict
      case (Left(accum), server) =>
        findConflict(server, accum) match {
          case Some(conflict) => Right((server, conflict))
          case None => Left(accum :+ server)
        }
    }
    // Return a conflict if one was found
    result match {
      case Left(_) => None
      case Right(conflict) => Some(conflict)
    }
  }

  object Params {
    val Ip = Parsing.Param.Text("ip") { ip =>
      Server.Ip.mk(ip)
    }

    val Port = Parsing.Param.Int("port") { p =>
      Server.Port(p)
    }

    def tlsParam(certificatePath: String, keyPath: String) =
      Transport.TLSServerEngine(
        Some(() => Ssl.server(certificatePath, keyPath, null, null, null))
      )

    val Tls = Parsing.Param("tls") { (json, params) =>
      case class TlsPaths(certPath: Option[String] = None, keyPath: Option[String] = None)

      val tlsPaths = Parsing.foldObject(json, TlsPaths()) {
        case (paths, "certPath", json) =>
          Parsing.ensureTok(json, JsonToken.VALUE_STRING) { json =>
            val path = json.getText
            json.nextToken()
            paths.copy(certPath = Some(path))
          }
        case (paths, "keyPath", json) =>
          Parsing.ensureTok(json, JsonToken.VALUE_STRING) { json =>
            val path = json.getText
            json.nextToken()
            paths.copy(keyPath = Some(path))
          }
      }

      tlsPaths match {
        case TlsPaths(Some(certPath), Some(keyPath)) =>
          params + tlsParam(certPath, keyPath)
        case _ => throw Parsing.error("tls object must contain certPath and keyPath.", json)
      }
    }

    val parser = Ip.andThen(Port).andThen(Tls)
  }
}
