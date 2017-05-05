package io.buoyant.telemetry

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.timgroup.statsd.NonBlockingStatsDClient
import com.twitter.finagle.Stack
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import io.buoyant.telemetry.statsd.{StatsDStatsReceiver, StatsDTelemeter}

class StatsDInitializer extends TelemeterInitializer {
  type Config = StatsDConfig
  val configClass = classOf[StatsDConfig]
  override val configId = "io.l5d.statsd"
}

private[telemetry] object StatsDConfig {
  val DefaultPrefix = "linkerd"
  val DefaultHostname = "127.0.0.1"
  val DefaultPort = 8125
  val DefaultGaugeIntervalMs = 10000 // for gauges
  val DefaultSampleRate = 0.01d // for counters and timing/histograms

  val MaxQueueSize = 10000
}

case class StatsDConfig(
  prefix: Option[String],
  hostname: Option[String],
  port: Option[Int],
  gaugeIntervalMs: Option[Int],
  @JsonDeserialize(contentAs = classOf[java.lang.Double]) sampleRate: Option[Double]
) extends TelemeterConfig {
  import StatsDConfig._

  @JsonIgnore override val experimentalRequired = true

  @JsonIgnore private[this] val log = Logger.get("io.l5d.statsd")

  @JsonIgnore private[this] val statsDPrefix = prefix.getOrElse(DefaultPrefix)
  @JsonIgnore private[this] val statsDHost = hostname.getOrElse(DefaultHostname)
  @JsonIgnore private[this] val statsDPort = port.getOrElse(DefaultPort)
  @JsonIgnore private[this] val statsDInterval = gaugeIntervalMs.getOrElse(DefaultGaugeIntervalMs)
  @JsonIgnore private[this] val statsDSampleRate = sampleRate.getOrElse(DefaultSampleRate)

  @JsonIgnore
  def mk(params: Stack.Params): StatsDTelemeter = {
    // initiate a UDP connection at startup time
    log.info(s"connecting to StatsD at $statsDHost:$statsDPort as $statsDPrefix")
    val statsDClient = new NonBlockingStatsDClient(
      statsDPrefix,
      statsDHost,
      statsDPort,
      MaxQueueSize
    )

    new StatsDTelemeter(
      new StatsDStatsReceiver(statsDClient, statsDSampleRate),
      statsDInterval,
      DefaultTimer
    )
  }
}
