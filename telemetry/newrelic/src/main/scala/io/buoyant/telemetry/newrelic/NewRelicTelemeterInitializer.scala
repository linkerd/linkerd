package io.buoyant.telemetry.newrelic

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Http, Name, Path, Stack}
import io.buoyant.telemetry.{MetricsTree, Telemeter, TelemeterConfig, TelemeterInitializer}
import java.net.InetAddress

class NewRelicTelemeterInitializer extends TelemeterInitializer {
  type Config = NewRelicConfig
  val configClass = classOf[NewRelicConfig]
  override val configId = "io.l5d.newrelic"
}

object NewRelicTelemeterInitializer extends NewRelicTelemeterInitializer

case class NewRelicConfig(
  license_key: String,
  host: Option[String],
  dst: Option[Path],
  prefix: Option[String]
) extends TelemeterConfig {
  import NewRelicConfig._
  assert(license_key != null, "License key must be provided.")

  @JsonIgnore override val experimentalRequired = true

  @JsonIgnore def mk(params: Stack.Params): Telemeter = {
    val client = Http.client
      .withParams(Http.client.params ++ params)
      .withSessionQualifier.noFailureAccrual
      .withSessionQualifier.noFailFast
      .withTracer(NullTracer)
      .withTls("platform-api.newrelic.com")
      .newService(Name.Path(dst.getOrElse(DefaultDst)), "newrelic")
    new NewRelicTelemeter(
      params[MetricsTree],
      client,
      license_key,
      host.getOrElse(InetAddress.getLocalHost.getCanonicalHostName),
      prefix.getOrElse("Linkerd"),
      DefaultTimer
    )
  }
}

object NewRelicConfig {
  val DefaultDst = Path.read("/$/inet/platform-api.newrelic.com/443")
}
