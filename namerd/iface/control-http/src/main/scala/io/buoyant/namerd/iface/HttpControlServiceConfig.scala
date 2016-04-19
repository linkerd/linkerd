package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Namer, Path, Http, ListeningServer}
import io.buoyant.namerd._
import java.net.{InetAddress, InetSocketAddress}

class HttpControlServiceConfig extends InterpreterInterfaceConfig {
  @JsonIgnore override protected def mk(
    delegate: Ns => NameInterpreter,
    namers: Map[Path, Namer],
    store: DtabStore
  ): Servable = HttpControlServable(addr, store, delegate)

  @JsonIgnore
  def defaultAddr = HttpControlServiceConfig.defaultAddr
}

object HttpControlServiceConfig {
  val kind = "httpController"
  val defaultAddr = new InetSocketAddress(InetAddress.getLoopbackAddress, 4180)
}

case class HttpControlServable(
  addr: InetSocketAddress,
  store: DtabStore,
  delegate: Ns => NameInterpreter
) extends Servable {
  def kind = HttpControlServiceConfig.kind
  def serve(): ListeningServer = Http.serve(addr, new HttpControlService(store, delegate))
}

class HttpControlServiceInitializer extends InterfaceInitializer {
  override val configId = HttpControlServiceConfig.kind
  val configClass = classOf[HttpControlServiceConfig]
}
