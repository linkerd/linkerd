package io.buoyant.namerd.iface

import com.twitter.finagle.{Http, ListeningServer, Namer, Path}
import io.buoyant.namerd._
import java.net.{InetAddress, InetSocketAddress}

class HttpControlServiceConfig extends InterfaceConfig {
  // note that `namers` is currently ignored here, but we may later wish to expose them via this interface
  def mk(store: DtabStore, namers: Map[Path, Namer]) = HttpControlServable(addr, store)
  def defaultAddr = HttpControlServiceConfig.defaultAddr
}

object HttpControlServiceConfig {
  val kind = "httpController"
  val defaultAddr = new InetSocketAddress(InetAddress.getLoopbackAddress, 4180)
}

case class HttpControlServable(addr: InetSocketAddress, store: DtabStore) extends Servable {
  def kind = HttpControlServiceConfig.kind
  def serve(): ListeningServer = Http.serve(addr, new HttpControlService(store))
}

class HttpControlServiceInitializer extends InterfaceInitializer {
  override val configId = HttpControlServiceConfig.kind
  val configClass = classOf[HttpControlServiceConfig]
}
