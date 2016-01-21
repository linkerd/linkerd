package io.l5d.experimental

import com.fasterxml.jackson.core.JsonParser
import com.twitter.finagle.param.Label
import com.twitter.finagle.{Http, Path, Stack}
import io.buoyant.consul.{CatalogNamer, v1, SetHostFilter}
import io.buoyant.linkerd.{NamerInitializer, Parsing}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.experimental.consul
 *   host: consul.site.biz
 *   port: 8600
 * </pre>
 */
object consul {
  /** The consul host; default: localhost */
  case class Host(host: String)
  implicit object Host extends Stack.Param[Host] {
    val default = Host("localhost")
    val parser = Parsing.Param.Text("host")(Host(_))
  }

  /** The consul port; default: 8500 */
  case class Port(port: Int)
  implicit object Port extends Stack.Param[Port] {
    val default = Port(8500)
    val parser = Parsing.Param.Int("port")(Port(_))
  }

  val parser = Parsing.Params(
    Host.parser,
    Port.parser
  )

  val defaultParams = Stack.Params.empty +
    NamerInitializer.Prefix(Path.Utf8("io.l5d.consul"))
}

/**
 * Configures a Consul namer.
 */
class consul(val params: Stack.Params) extends NamerInitializer {
  def this() = this(consul.defaultParams)
  def withParams(ps: Stack.Params) = new consul(ps)

  def paramKeys = consul.parser.keys
  def readParam(k: String, p: JsonParser) =
    withParams(consul.parser.read(k, p, params))

  /**
   * Build a Namer backed by Consul.
   */
  def newNamer() = {
    val consul.Host(host) = params[consul.Host]
    val consul.Port(port) = params[consul.Port]
    val path = params[NamerInitializer.Prefix].path.show
    val service = Http.client
      .configured(Label("namer" + path))
      .filtered(new SetHostFilter(host, port))
      .newService(s"/$$/inet/$host/$port")

    def mkNs(ns: String) = v1.Api(service)
    new CatalogNamer(prefix, mkNs)
  }
}
