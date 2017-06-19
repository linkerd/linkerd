package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Http, ListeningServer, Namer, Path, Stack}
import com.twitter.finagle.param
import io.buoyant.namerd._
import java.net.{InetAddress, InetSocketAddress}

class HttpControlServiceConfig extends InterpreterInterfaceConfig {
  @JsonIgnore override protected def mk(
    delegate: Ns => NameInterpreter,
    namers: Map[Path, Namer],
    store: DtabStore,
    stats: StatsReceiver
  ): Servable = {
    val iface = new HttpControlService(store, delegate, namers)
    val params =
      tlsParams +
        param.Stats(stats.scope(HttpControlServiceConfig.kind)) +
        param.Label(HttpControlServiceConfig.kind) +
        Http.Netty4Impl

    HttpControlServable(addr, iface, params)
  }

  @JsonIgnore
  def defaultAddr = HttpControlServiceConfig.defaultAddr
}

object HttpControlServiceConfig {
  val kind = "io.l5d.httpController"
  val defaultAddr = new InetSocketAddress(InetAddress.getLoopbackAddress, 4180)
}

case class HttpControlServable(
  addr: InetSocketAddress,
  iface: HttpControlService,
  params: Stack.Params
) extends Servable {
  def kind = HttpControlServiceConfig.kind
  val http = Http.server
  def serve(): ListeningServer = http
    .withParams(http.params ++ params)
    .withStreaming(true)
    .serve(addr, iface)
}

class HttpControlServiceInitializer extends InterfaceInitializer {
  override val configId = HttpControlServiceConfig.kind
  val configClass = classOf[HttpControlServiceConfig]
}
