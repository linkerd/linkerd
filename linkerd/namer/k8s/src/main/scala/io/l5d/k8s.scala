package io.l5d.experimental

import com.fasterxml.jackson.core.JsonParser
import com.twitter.finagle.{Filter, Http, Path, Service, SimpleFilter, Stack}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.param.Label
import io.buoyant.k8s.{EndpointsNamer, SetHostFilter}
import io.buoyant.k8s.v1.{Api => K8sApi}
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
   * default: /var/run/secrets/kubernetes.io/serviceaccount/token
   */
  case class AuthTokenFile(path: String) {
    def load(): String = Source.fromFile(path).mkString
    def filter(): Filter[Request, Response, Request, Response] = AuthTokenFile.Filter(load())
  }

  implicit object AuthTokenFile extends Stack.Param[AuthTokenFile] {
    // Kubernetes mounts a secrets volume with master authentication
    // tokens.  That's usually what we want.
    val default = AuthTokenFile("/var/run/secrets/kubernetes.io/serviceaccount/token")
    val parser = Parsing.Param.Text("authTokenFile")(AuthTokenFile(_))

    private case class Filter(token: String) extends SimpleFilter[Request, Response] {
      def apply(req: Request, service: Service[Request, Response]) = {
        req.headerMap("Authorization") = s"Bearer $token"
        service(req)
      }
    }
  }

  val parser = Parsing.Params(
    Host.parser,
    Port.parser,
    Tls.parser,
    Tls.WithoutValidation.parser,
    AuthTokenFile.parser
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
      case (_, k8s.Tls.WithoutValidation(true)) => Http.client.withTlsWithoutValidation()
      case _ => Http.client.withTls(setHost.host)
    }

    // namer path -- should we just support a `label`?
    val path = params[NamerInitializer.Prefix].path.show
    val service = client
      .configured(Label("namer" + path))
      .filtered(setHost)
      .filtered(params[k8s.AuthTokenFile].filter())
      .withStreaming(true)
      .newService(s"/$$/inet/$host/$port")

    def mkNs(ns: String) = K8sApi(service).namespace(ns)
    new EndpointsNamer(prefix, mkNs)
  }
}
