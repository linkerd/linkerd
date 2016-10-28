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

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind:      io.l5d.marathon
 *   experimental: true
 *   prefix:    /io.l5d.marathon
 *   host:      marathon.mesos
 *   port:      80
 *   uriPrefix: /marathon
 *   ttlMs:     5000
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

case class MarathonConfig(
  host: Option[String],
  port: Option[Port],
  dst: Option[String],
  uriPrefix: Option[String],
  ttlMs: Option[Int]
) extends NamerConfig {

  private[this] val log = Logger.get(getClass.getName)

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.marathon")

  private[this] def getHost = host.getOrElse("marathon.mesos")
  private[this] def getPort = port match {
    case Some(p) => p.port
    case None => 80
  }
  private[this] def getUriPrefix = uriPrefix.getOrElse("")
  private[this] def getTtl = ttlMs.getOrElse(5000).millis

  private[this] def getDst = dst.getOrElse(s"/$$/inet/$getHost/$getPort")

  // canonical environment variable holding marathon auth info
  // example secret json object:
  // {
  //   "login_endpoint": "https://leader.mesos/acs/api/v1/auth/login",
  //   "private_key": "<private-key-value>",
  //   "scheme": "RS256",
  //   "uid": "service-acct"
  // }
  // more info at:
  // https://docs.mesosphere.com/1.8/administration/id-and-access-mgt/service-auth/custom-service-auth/
  // https://github.com/mesosphere/universe/search?utf8=%E2%9C%93&q=DCOS_SERVICE_ACCOUNT_CREDENTIAL
  private[this] val SecretKey = "DCOS_SERVICE_ACCOUNT_CREDENTIAL"
  private[this] def getAuth =
    sys.env.get(SecretKey).flatMap { secret =>
      Api.readJson[MarathonSecret](Buf.Utf8(secret)) match {
        case Return(MarathonSecret(Some(loginEndpoint), Some(privateKey), Some("RS256"), Some(uid))) =>
          Some(Authenticator.AuthRequest(loginEndpoint, uid, privateKey))
        case Throw(e) =>
          log.error(e, "readJson error")
          throw e
        case _ =>
          val e = new Exception(s"unexpected format for $SecretKey")
          log.error(e, s"unexpected format for $SecretKey")
          throw e
      }
    }

  private[this] case class SetHost(host: String)
    extends SimpleFilter[http.Request, http.Response] {

    def apply(req: http.Request, service: Service[http.Request, http.Response]) = {
      req.host = host
      service(req)
    }
  }

  /**
   * Construct a namer.
   */
  def newNamer(params: Stack.Params) = {
    val client = SetHost(getHost).andThen(
      Http.client
        .withParams(params)
        .configured(Label("namer" + prefix.show))
        .withTracer(NullTracer)
        .newService(getDst)
    )
    val service = getAuth match {
      case Some(authRequest) => new Authenticator.Authenticated(client, authRequest)
      case None => client
    }

    new AppIdNamer(Api(service, getUriPrefix), prefix, getTtl)
  }
}
