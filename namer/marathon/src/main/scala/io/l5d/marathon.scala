package io.l5d.experimental

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.param.Label
import com.twitter.finagle.{Stack, Http, Path}
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.marathon.v2.{Api, AppIdNamer}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind:      io.l5d.experimental.marathon
 *   prefix:    /io.l5d.marathon
 *   host:      marathon.mesos
 *   port:      80
 *   uriPrefix: /marathon
 * </pre>
 */
class MarathonInitializer extends NamerInitializer {
  val configClass = classOf[marathon]
}

object MarathonInitializer extends MarathonInitializer

case class marathon(
  host: Option[String],
  port: Option[Port],
  uriPrefix: Option[String]
) extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.marathon")

  private[this] def getHost = host.getOrElse("marathon.mesos")
  private[this] def getPort = port match {
    case Some(p) => p.port
    case None => 80
  }
  private[this] def getUriPrefix = uriPrefix.getOrElse("")

  /**
   * Construct a namer.
   */
  def newNamer(params: Stack.Params) = {
    val service = Http.client
      .withParams(params)
      .configured(Label("namer" + prefix.show))
      .newService(s"/$$/inet/$getHost/$getPort")

    new AppIdNamer(Api(service, getHost, getUriPrefix), prefix, 250.millis)
  }
}
