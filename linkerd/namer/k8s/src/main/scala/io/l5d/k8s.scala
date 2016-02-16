package io.l5d.experimental

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.param.Label
import io.buoyant.k8s.{AuthFilter, EndpointsNamer, SetHostFilter}
import io.buoyant.k8s.v1.{Api}
import io.buoyant.linkerd.{NamerInitializer, Parsing}
import scala.io.Source

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.experimental.k8s
 *   host: k8s-master.site.biz
 *   port: 80
 *   tls: false
 *   authTokenFile: ../auth.token
 * </pre>
 */
object k8s {

  /** The kubernetes master host; default: kubenretes.default.cluster.local */
  case class Host(host: String)
  implicit object Host extends Stack.Param[Host] {
    val default = Host("kubernetes.default.cluster.local")
    val parser = Parsing.Param.Text("host")(Host(_))
  }

  /** The kubernetes master port; default: 443 (https) */
  case class Port(port: Int)
  implicit object Port extends Stack.Param[Port] {
    val default = Port(443)
    val parser = Parsing.Param.Int("port")(Port(_))
  }

  /** Whether TLS is used to communicate with the master; default: true */
  case class Tls(enabled: Boolean)
  implicit object Tls extends Stack.Param[Tls] {
    val default = Tls(true)
    val parser = Parsing.Param.Boolean("tls")(Tls(_))

    /** Whether hostname validation is performed with TLS. */
    case class WithoutValidation(enabled: Boolean)
    implicit object WithoutValidation extends Stack.Param[WithoutValidation] {
      val default = WithoutValidation(false)
      val parser = Parsing.Param.Boolean("tlsWithoutValidation")(WithoutValidation(_))
    }
  }

  /**
   * The path to a file containing the k8s master's authorization token.
   * default: none
   */
  case class AuthToken(token: String) {
    def filter(): Filter[Request, Response, Request, Response] = token match {
      case "" => Filter.identity[Request, Response]
      case path => new AuthFilter(token)
    }
  }

  implicit object AuthToken extends Stack.Param[AuthToken] {
    // Kubernetes mounts a secrets volume with master authentication
    // tokens.  That's usually what we want.
    val default = AuthToken("")

    val parser = Parsing.Param("authTokenFile") { (parser, params) =>
      Parsing.ensureTok(parser, JsonToken.VALUE_STRING) { parser =>
        val path = parser.getText
        parser.nextToken()
        params + AuthToken(Source.fromFile(path).mkString)
      }
    }
  }

  val parser = Parsing.Params(
    Host.parser,
    Port.parser,
    Tls.parser,
    Tls.WithoutValidation.parser,
    AuthToken.parser
  )

  val defaultParams = Stack.Params.empty +
    NamerInitializer.Prefix(Path.Utf8("io.l5d.k8s"))
}

/**
 * Configures a kubernetes namer.
 */
class k8s(val params: Stack.Params) extends NamerInitializer {
  def this() = this(k8s.defaultParams)
  def withParams(ps: Stack.Params) = new k8s(ps)
  def withAuthToken(tok: String) = withParams(params + k8s.AuthToken(tok))

  def paramKeys = k8s.parser.keys
  def readParam(k: String, p: JsonParser) =
    withParams(k8s.parser.read(k, p, params))

  /**
   * Build a Namer backed by a Kubernetes master.
   */
  def newNamer() = {
    val k8s.Host(host) = params[k8s.Host]
    val k8s.Port(port) = params[k8s.Port]
    val setHost = new SetHostFilter(host, port)

    val client = (params[k8s.Tls], params[k8s.Tls.WithoutValidation]) match {
      case (k8s.Tls(false), _) => Http.client
      case (_, k8s.Tls.WithoutValidation(true)) => Http.client.withTlsWithoutValidation
      case _ => Http.client.withTls(setHost.host)
    }

    // namer path -- should we just support a `label`?
    val path = params[NamerInitializer.Prefix].path.show
    val auth = params[k8s.AuthToken].filter()
    val service = client
      .configured(Label("namer" + path))
      .filtered(setHost)
      .filtered(auth)
      .withStreaming(true)
      .newService(s"/$$/inet/$host/$port")

    def mkNs(ns: String) = Api(service).namespace(ns)
    new EndpointsNamer(prefix, mkNs)
  }
}
