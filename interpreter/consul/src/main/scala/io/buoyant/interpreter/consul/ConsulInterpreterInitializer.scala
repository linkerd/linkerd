package io.buoyant.interpreter.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Http, Path, Stack}
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.tracing.NullTracer
import com.twitter.conversions.time._
import io.buoyant.config.types.Port
import io.buoyant.consul.utils.RichConsulClient
import io.buoyant.consul.v1.{ConsistencyMode, KvApi}
import io.buoyant.namer.{BackoffConfig, ConfiguredDtabNamer, InterpreterConfig, InterpreterInitializer, Param}

class ConsulInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[ConsulDtabInterpreterConfig]

  override def configId: String = "io.l5d.consul.dtab"
}

object ConsulInterpreterInitializer extends ConsulInterpreterInitializer

case class ConsulDtabInterpreterConfig(
  host: Option[String],
  port: Option[Port],
  pathPrefix: Option[Path],
  namespace: String,
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
  private val api = {
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
  private val cache = new ConsulDtabCache(
    api,
    pathPrefix.getOrElse(Path.read("/namerd/dtabs")),
    datacenter,
    readConsistencyMode,
    writeConsistencyMode
  )

  @JsonIgnore
  private val dtab = cache.observe(namespace).map {
    case None => Dtab.empty
    case Some(dtab) => dtab
  }

  @JsonIgnore
  override protected def newInterpreter(params: Stack.Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredDtabNamer(
      dtab,
      namers
    )
  }
}

object ConsulDtabInterpreterConfig {
  val DefaultHost = "localhost"
  val DefaultPort = Port(8500)
  val DefaultBackoff = Backoff.decorrelatedJittered(1.millis, 1.minute)
}

