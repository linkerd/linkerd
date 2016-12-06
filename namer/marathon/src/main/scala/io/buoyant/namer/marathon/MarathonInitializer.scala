package io.buoyant.namer.marathon

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.param.Label
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util.{Return, Throw}
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.marathon.v2.{Api, AppIdNamer}
import scala.util.control.NoStackTrace

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind:           io.l5d.marathon
 *   experimental:   true
 *   prefix:         /io.l5d.marathon
 *   host:           marathon.mesos
 *   port:           80
 *   uriPrefix:      /marathon
 *   ttlMs:          5000
 *   useHealthCheck: false
 * </pre>
 */
class MarathonInitializer extends NamerInitializer {
  val configClass = classOf[MarathonConfig]
  override def configId = "io.l5d.marathon"
}

object MarathonInitializer extends MarathonInitializer

case class MarathonSecret(
  login_endpoint: Option[String],
  private_key: Option[String],
  scheme: Option[String],
  uid: Option[String]
)

/**
 * Marathon credentials are encoded as JSON objects, e.g:
 *
 *  {
 *    "login_endpoint": "https://leader.mesos/acs/api/v1/auth/login",
 *    "private_key": "<private-key-value>",
 *    "scheme": "RS256",
 *    "uid": "service-acct"
 *  }
 *
 * This JSON blob is stored in the `DCOS_SERVICE_ACCOUNT_CREDENTIAL` environment variable.
 *
 * See also:
 *   - https://docs.mesosphere.com/1.8/administration/id-and-access-mgt/service-auth/custom-service-auth/
 *   - https://github.com/mesosphere/universe/search?utf8=%E2%9C%93&q=DCOS_SERVICE_ACCOUNT_CREDENTIAL
 */
object MarathonSecret {
  val EnvKey = "DCOS_SERVICE_ACCOUNT_CREDENTIAL"

  case class Invalid(secret: MarathonSecret) extends NoStackTrace

  def mkAuthRequest(s: MarathonSecret): Authenticator.AuthRequest = s match {
    case MarathonSecret(Some(loginEndpoint), Some(privateKey), Some("RS256"), Some(uid)) =>
      Authenticator.AuthRequest(loginEndpoint, uid, privateKey)
    case s =>
      throw Invalid(s)
  }

  def load(): Option[MarathonSecret] =
    sys.env.get(EnvKey) match {
      case None => None
      case Some(json) =>
        Api.readJson[MarathonSecret](Buf.Utf8(json)) match {
          case Throw(e) => throw e
          case Return(secret) => Some(secret)
        }
    }
}

object MarathonConfig {
  private val DefaultHost = "marathon.mesos"
  private val DefaultPrefix = Path.read("/io.l5d.marathon")

  private case class SetHost(host: String) extends SimpleFilter[http.Request, http.Response] {
    def apply(req: http.Request, service: Service[http.Request, http.Response]) = {
      req.host = host
      service(req)
    }
  }

}

case class MarathonConfig(
  host: Option[String],
  port: Option[Port],
  dst: Option[String],
  uriPrefix: Option[String],
  ttlMs: Option[Int],
  useHealthCheck: Option[Boolean]
) extends NamerConfig {
  import MarathonConfig._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def defaultPrefix: Path = DefaultPrefix

  /**
   * Construct a namer.
   */
  def newNamer(params: Stack.Params) = {
    val host0 = host.getOrElse(DefaultHost)
    val port0 = port.map(_.port).getOrElse(80)
    val dst0 = dst.getOrElse(s"/$$/inet/$host0/$port0")

    val client = Http.client
      .withParams(params)
      .configured(Label("namer" + prefix.show))
      .withTracer(NullTracer)
      .filtered(SetHost(host0))
      .newService(dst0)

    val service = MarathonSecret.load() match {
      case None => client
      case Some(secret) =>
        val auth = MarathonSecret.mkAuthRequest(secret)
        new Authenticator.Authenticated(client, auth)
    }

    val uriPrefix0 = uriPrefix.getOrElse("")
    val useHealthCheck0 = useHealthCheck.getOrElse(false)
    val api = Api(service, uriPrefix0, useHealthCheck0)

    val ttl = ttlMs.getOrElse(5000).millis
    new AppIdNamer(api, prefix, ttl)
  }
}
