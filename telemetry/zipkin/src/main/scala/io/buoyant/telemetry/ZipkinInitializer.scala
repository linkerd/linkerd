package io.buoyant.telemetry

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.finagle._
import com.twitter.finagle.buoyant.zipkin.ZipkinTracer
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing._
import com.twitter.finagle.zipkin.core.Sampler

class ZipkinInitializer extends TelemeterInitializer {
  type Config = ZipkinConfig
  val configClass = classOf[ZipkinConfig]
  override val configId = "io.l5d.zipkin"
}

case class ZipkinConfig(
  host: Option[String],
  port: Option[Int],
  @JsonDeserialize(contentAs = classOf[java.lang.Double]) sampleRate: Option[Double]
) extends TelemeterConfig {

  private[this] val tracer: Tracer = new ZipkinTracer(
    host.getOrElse("localhost"),
    port.getOrElse(9410),
    sampleRate.map(_.toFloat).getOrElse(Sampler.DefaultSampleRate)
  )

  def mk(params: Stack.Params): ZipkinTelemeter = new ZipkinTelemeter(tracer)
}

class ZipkinTelemeter(underlying: Tracer) extends Telemeter {
  val stats = NullStatsReceiver
  lazy val tracer = underlying

  def run() = Telemeter.nopRun
}
