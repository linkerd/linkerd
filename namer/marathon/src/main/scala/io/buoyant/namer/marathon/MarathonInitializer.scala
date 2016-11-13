package io.buoyant.namer.marathon

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.param.Label
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Http, Path, Stack}
import com.twitter.util.Duration
import io.buoyant.config.types.Port
import io.buoyant.marathon.v2.{Api, AppIdNamer}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

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
 *   jitterMs:  50
 * </pre>
 */
class MarathonInitializer extends NamerInitializer {
  val configClass = classOf[MarathonConfig]
  override def configId = "io.l5d.marathon"
}

object MarathonInitializer extends MarathonInitializer

case class MarathonConfig(
  host: Option[String],
  port: Option[Port],
  dst: Option[String],
  uriPrefix: Option[String],
  ttlMs: Option[Int],
  jitterMs: Option[Int]
) extends NamerConfig {
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

  @JsonIgnore
  private[this] def getJitteredTtl(ttl: Int, jitterConf: Int): Stream[Duration] = {
    require(ttl > jitterConf, "TTL should be greater than jitter")
    val jitter = (ttl + (scala.util.Random.nextDouble() * 2 - 1) * jitterConf).toInt
    Stream.continually(jitter.millis)
  }
  private[this] def getTtl: Stream[Duration] = {
    getJitteredTtl(ttlMs.getOrElse(5000), jitterMs.getOrElse(0))
  }

  private[this] def getDst = dst.getOrElse(s"/$$/inet/$getHost/$getPort")

  /**
   * Construct a namer.
   */
  def newNamer(params: Stack.Params) = {
    val service = Http.client
      .withParams(params)
      .configured(Label("namer" + prefix.show))
      .withTracer(NullTracer)
      .newService(getDst)

    new AppIdNamer(Api(service, getHost, getUriPrefix), prefix, getTtl)
  }
}
