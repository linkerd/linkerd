package io.buoyant.interpreter.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.tracing.NullTracer
import com.twitter.conversions.time._
import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.util.Future
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.consul.utils.RichConsulClient
import io.buoyant.consul.v1.InstrumentedApiCall.mkPollState
import io.buoyant.consul.v1.{ConsistencyMode, Indexed, KvApi}
import io.buoyant.namer.{BackoffConfig, ConfiguredDtabNamer, InterpreterConfig, InterpreterInitializer, Param}

class ConsulInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[ConsulDtabInterpreterConfig]

  override def configId: String = "io.l5d.consul.interpreter"
}

object ConsulInterpreterInitializer extends ConsulInterpreterInitializer

case class ConsulDtabInterpreterConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path],
  namespace: Option[String],
  token: Option[String] = None,
  datacenter: Option[String] = None,
  readConsistencyMode: Option[ConsistencyMode] = None,
  writeConsistencyMode: Option[ConsistencyMode] = None,
  failFast: Option[Boolean] = None,
  backoff: Option[BackoffConfig] = None,
  tls: Option[TlsClientConfig] = None
) extends InterpreterConfig {

  import ConsulDtabInterpreterConfig._

  @JsonIgnore
  private[this] val pollState = mkPollState[Indexed[String]]

  @JsonIgnore
  private[this] val adminUri = "/interpreter_state/io.l5d.consul.interpreter.json"

  @JsonIgnore
  private[this] val api = {
    val serviceHost = host.getOrElse(DefaultHost)
    val servicePort = port.getOrElse(DefaultPort).port
    val backoffs = backoff.map(_.mk).getOrElse(DefaultBackoff)
    val tlsParams = tls.map(_.params).getOrElse(Stack.Params.empty)
    val service = Http.client
      .interceptInterrupts
      .failFast(failFast)
      .setAuthToken(token)
      .ensureHost(host, port)
      .withTracer(NullTracer)
      .withParams(tlsParams)
      .newService(s"/$$/inet/$serviceHost/$servicePort")

    KvApi(service, backoffs)
  }

  @JsonIgnore
  private[this] val cache = new ConsulDtabCache(
    api,
    pathPrefix.getOrElse(Path.read("/namerd/dtabs")),
    datacenter,
    readConsistencyMode,
    writeConsistencyMode,
    pollState
  )

  @JsonIgnore
  private[this] val dtab = cache.observe(namespace.getOrElse(DefaultNamespace)).underlying.map {
    case None => Dtab.empty
    case Some(dtab) => dtab
  }

  @JsonIgnore
  private[this] val watchStateHandler = new Service[Request, Response] {
    val mapper = Parser.jsonObjectMapper(Nil)

    override def apply(request: Request): Future[Response] = {
      val rep = Response(Status.Ok)
      val watchState = Map(
        "state" -> cache.observe(namespace.getOrElse(DefaultNamespace)).stateSnapshot(),
        "poll" -> pollState
      )

      rep.contentString = mapper.writeValueAsString(watchState)
      rep.mediaType = MediaType.Json
      Future.value(rep)
    }
  }

  private[this] val handlers = Seq(Admin.Handler(adminUri, watchStateHandler))

  @JsonIgnore
  override def newInterpreter(params: Stack.Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredDtabNamer(
      dtab,
      namers,
      handlers
    )
  }
}

object ConsulDtabInterpreterConfig {
  private[consul] val DefaultHost = "localhost"
  private[consul] val DefaultPort = Port(8500)
  private[consul] val DefaultBackoff = Backoff.decorrelatedJittered(1.millis, 1.minute)
  private[consul] val DefaultNamespace = "default"
}

