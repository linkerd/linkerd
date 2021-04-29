package io.buoyant.interpreter.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.tracing.NullTracer
import com.twitter.conversions.DurationOps._
import io.buoyant.config.types.Port
import io.buoyant.consul.utils.RichConsulClient
import io.buoyant.consul.v1.InstrumentedApiCall.mkPollState
import io.buoyant.consul.v1.{ConsistencyMode, Indexed, KvApi}
import io.buoyant.namer.{BackoffConfig, ConfiguredDtabNamer, InterpreterConfig, InterpreterInitializer, Param}
import io.buoyant.namerd.VersionedDtab
import io.buoyant.namerd.storage.consul.ConsulDtabStore

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
  private[this] val dtabNamespace = namespace.getOrElse(DefaultNamespace)

  @JsonIgnore
  private[this] val adminUri = "/interpreter_state/io.l5d.consul.interpreter.json"

  @JsonIgnore
  private[this] lazy val api = {
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
  private[this] lazy val cache = new ConsulDtabStore(
    api,
    pathPrefix.getOrElse(Path.read("/namerd/dtabs")),
    datacenter,
    readConsistencyMode,
    writeConsistencyMode,
    adminUri
  )

  @JsonIgnore
  private[this] lazy val dtab = cache.observe(dtabNamespace).map {
    case None => Dtab.empty
    case Some(VersionedDtab(dtab, _)) => dtab
  }

  @JsonIgnore
  override def newInterpreter(params: Stack.Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredDtabNamer(
      dtab,
      namers,
      cache.adminHandlers
    )
  }
}

object ConsulDtabInterpreterConfig {
  private[consul] val DefaultHost = "localhost"
  private[consul] val DefaultPort = Port(8500)
  private[consul] val DefaultBackoff = Backoff.decorrelatedJittered(1.millis, 1.minute)
  private[consul] val DefaultNamespace = "default"
}

