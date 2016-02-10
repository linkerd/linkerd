package io.buoyant.linkerd

import com.fasterxml.jackson.core.{io => _, _}
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.service.{FailFastFactory, TimeoutFilter}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.Closable
import io.buoyant.router.RoutingFactory
import java.net.{InetAddress, InetSocketAddress}

/**
 * A router configuration builder api.
 *
 * Each router must have a [[ProtocolInitializer protocol]] that
 * assists in the parsing and intialization of a router and its
 * services.
 *
 * `params` contains all params configured on this router, including
 * (in order of ascending preference):
 *  - protocol-specific default router parameters
 *  - linker default parameters
 *  - router-specific params.
 *
 * Each router must have one or more [[Server Servers]].
 *
 * Concrete implementations are provided by a [[ProtocolInitializer]].
 */
trait Router {
  def protocol: ProtocolInitializer

  // configuration
  def params: Stack.Params
  def withParams(ps: Stack.Params): Router
  def configured[P: Stack.Param](p: P): Router = withParams(params + p)
  def configured(ps: Stack.Params): Router = withParams(params ++ ps)

  def paramParser: Parsing.Params

  // helper aliases
  def label: String = params[param.Label].label

  // servers
  def servers: Seq[Server]
  protected def withServers(servers: Seq[Server]): Router

  /** Return a router with an additional server. */
  def serving(s: Server): Router = withServers(servers :+ Router.configureServer(this, s))

  def serving(ss: Seq[Server]): Router = ss.foldLeft(this)(_ serving _)

  /** Return a router with additional servers read from the provided parser. */
  def servingFrom(jp: JsonParser): Router =
    serving(Router.readServers(jp, protocol.server))

  /** Return a router with client configuration read from the provided parser. */
  def clientFrom(tls: TlsClientInitializers, p: JsonParser): Router

  /**
   * Initialize a router by instantiating a downstream router client
   * so that its upstream `servers` may be bound.
   */
  def initialize(): Router.Initialized
}

object Router {
  /**
   * A [[Router]] that has been configured and initialized.
   *
   * Concrete implementations
   */
  trait Initialized extends Closable {
    def protocol: ProtocolInitializer
    def params: Stack.Params
    def servers: Seq[Server.Initializer]
  }

  /**
   * Read a Router in the form:
   *
   * <pre>
   *     protocol: proto
   *     label: foo
   *     protoSpecificParam: true
   *     servers:
   *     - port: 4140
   * </pre>
   *
   * The provided `linkerParams` are global router defaults (i.e. as
   * specified on a [[Linker]]).
   *
   * Each named `protocol` must exist in the provided
   * [[ProtocolInitializers]] or an exception is thrown.
   *
   * If a `label` router param is not set, the protocol name is used.
   *
   * A router's `servers` must not have overlapping ip/port
   * configurations. For example, the following configuration is
   * invalid:
   *
   * <pre>
   *     servers:
   *     - port: 4140
   *     - port: 4140
   *       ip: any
   * </pre>
   *
   * Whereas the following configuration _is_ allowed since its IPs do
   * not overlap:
   *
   * <pre>
   *     servers:
   *     - port: 4140
   *       ip: 127.0.0.1
   *     - port: 4140
   *       ip: 192.168.99.100
   * </pre>
   *
   * If `servers` is not specified and the router's protocol has a
   * sufficiently-defined default server configuration--notably, it
   * has a `Server.Port`--then the default server configuration is
   * used.  An exception is thrown if at least one server cannot be
   * loaded.
   */
  def read(
    origParser: JsonParser,
    linkerParams: Stack.Params,
    protocols: ProtocolInitializers,
    tls: TlsClientInitializers
  ): Router = {
    val routerTree =
      Parsing.ensureTok(origParser, JsonToken.START_OBJECT) { json =>
        origParser.readValueAsTree(): TreeNode
      }

    // Ensure that 'protocol' is parsed before params or 'servers'
    val proto = {
      val json = routerTree.traverse()
      json.setCodec(origParser.getCodec)
      json.nextToken()
      json.overrideCurrentName("router")
      Parsing.findInObject(json) {
        case ("protocol", json) => protocols.read(json)
      } match {
        case None => throw Parsing.error("router must have a 'protocol", json)
        case Some(proto) => proto
      }
    }

    // The base router params include an inferred default of the protocol name.
    var router = proto.router
      .configured(param.Label(proto.name))
      .configured(linkerParams)

    router = {
      val json = routerTree.traverse()
      json.setCodec(origParser.getCodec)
      json.nextToken()
      json.overrideCurrentName("router")
      Parsing.foldObject(json, router) {
        case (router, "protocol" | "servers", json) =>
          Parsing.skipValue(json)
          router

        case (router, "client", json) =>
          router.clientFrom(tls, json)

        case (router, key, json) if router.paramParser.keys(key) =>
          val params = router.paramParser.read(key, json, router.params)
          router.withParams(params)

        case (_, key, json) =>
          throw Parsing.error(s"router has unexpected parameter: $key", json)
      }
    }

    // Complete router loading by reading the server list.  This is
    // done after params have been read so that the router's label is
    // configured.
    router = {
      val json = routerTree.traverse()
      json.setCodec(origParser.getCodec)
      json.nextToken()
      json.overrideCurrentName("router")
      Parsing.foldObject(json, router) {
        case (router, "servers", json) =>
          router.servingFrom(json)

        case (router, _, json) =>
          Parsing.skipValue(json)
          router
      }
    }

    // Set the default server if no servers were specified
    if (router.servers.isEmpty) {
      router = router.serving(router.protocol.server)
    }

    // Validate servers.
    for (server <- router.servers)
      server.params[Server.Port] match {
        case Server.Port.Unknown =>
          throw Parsing.error(s"server '${server.label}' has no port", origParser)
        case _ =>
      }

    origParser.nextToken()
    router
  }

  private def configureServer(router: Router, server: Server): Server = {
    val ip = server.ip.getHostAddress
    val port = server.params[Server.Port] match {
      case Server.Port.Unknown => throw new IllegalArgumentException("server must have 'port'")
      case Server.Port.Specified(port) => port
    }
    val param.Stats(stats) = router.params[param.Stats]
    val routerLabel = router.label
    server.configured(param.Label(s"$ip/$port"))
      .configured(Server.RouterLabel(routerLabel))
      .configured(param.Stats(stats.scope(routerLabel, "srv")))
      .configured(router.params[param.Tracer])
  }

  private def readServers(json: JsonParser, base: Server): Seq[Server] = {
    Parsing.foldArray(json, Seq.empty[Server]) {
      case (servers, json) =>
        json.overrideCurrentName("server")
        val server = base.configuredFrom(json)

        Server.findConflict(server, servers) match {
          case Some(other) =>
            throw Parsing.error(s"server conflict on ${server.addr} and ${other.addr}", json)

          case None =>
            servers :+ server
        }
    }
  }

  object Params {
    val Label = Parsing.Param.Text("label")(param.Label(_))

    val BaseDtab = Parsing.Param.Text("baseDtab") { t =>
      val dtab = Dtab.read(t)
      RoutingFactory.BaseDtab(() => dtab)
    }

    // TODO This was difficult because it implies ordering constraints.
    //
    // val AddDtab = Parsing.Param("addDtab") { (json, params) =>
    //   Option(json.nextTextValue) match {
    //     case None => throw Parsing.error(s"'addDtab' must have a textual value", json)
    //     case Some(text) =>
    //       val addendum = Dtab.read(text)
    //       val RoutingFactory.BaseDtab(orig) = params[RoutingFactory.BaseDtab]
    //       params + RoutingFactory.BaseDtab(() => orig() ++ addendum)
    //   }
    // }

    val DstPrefix = Parsing.Param.Text("dstPrefix") { pfx =>
      RoutingFactory.DstPrefix(Path.read(pfx))
    }

    val FailFast = Parsing.Param.Boolean("failFast") { failFast =>
      FailFastFactory.FailFast(failFast)
    }

    val TimeoutMs = Parsing.Param.Long("timeoutMs") { timeout =>
      TimeoutFilter.Param(timeout.millis)
    }

    /** Parses params that apply to all routers */
    val parser = Parsing.Params(
      Params.Label,
      Params.BaseDtab,
      //Params.AddDtab,
      Params.DstPrefix,
      Params.FailFast,
      Params.TimeoutMs
    )
  }
}
