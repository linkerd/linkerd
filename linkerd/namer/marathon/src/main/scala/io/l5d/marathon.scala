package io.l5d.experimental

import com.fasterxml.jackson.core.JsonParser
import com.twitter.conversions.time._
import com.twitter.finagle.param.Label
import com.twitter.finagle.{Http, Stack, Path}
import io.buoyant.linkerd.{NamerInitializer, Parsing}
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
object marathon {

  /** The mesos master host; default: marathon.mesos */
  case class Host(host: String)
  implicit object Host extends Stack.Param[Host] {
    val default = Host("marathon.mesos")
    val parser = Parsing.Param.Text("host")(Host(_))
  }

  /** The mesos master port; default: 80 */
  case class Port(port: Int)
  implicit object Port extends Stack.Param[Port] {
    val default = Port(80)
    val parser = Parsing.Param.Int("port")(Port(_))
  }

  /** URI Prefix; default: empty string */
  case class UriPrefix(uriPrefix: String)
  implicit object UriPrefix extends Stack.Param[UriPrefix] {
    val default = UriPrefix("")
    val parser = Parsing.Param.Text("uriPrefix")(UriPrefix(_))
  }

  val parser = Parsing.Params(
    Host.parser,
    Port.parser,
    UriPrefix.parser
  )

  val defaultParams = Stack.Params.empty +
    NamerInitializer.Prefix(Path.Utf8("io.l5d.marathon"))
}

/**
 * Configures a Marathon namer.
 */
class marathon(val params: Stack.Params) extends NamerInitializer {
  def this() = this(marathon.defaultParams)
  def withParams(ps: Stack.Params) = new marathon(ps)

  def paramKeys = marathon.parser.keys
  def readParam(k: String, p: JsonParser) =
    withParams(marathon.parser.read(k, p, params))

  /**
   * Build a Namer backed by Marathon.
   */
  def newNamer() = {
    val marathon.Host(host) = params[marathon.Host]
    val marathon.Port(port) = params[marathon.Port]
    val marathon.UriPrefix(uriPrefix) = params[marathon.UriPrefix]
    val NamerInitializer.Prefix(path) = params[NamerInitializer.Prefix]
    val service = Http.client
      .configured(Label("namer" + path.show))
      .newService(s"/$$/inet/$host/$port")

    new AppIdNamer(Api(service, host, uriPrefix), prefix, 250.millis)
  }
}
