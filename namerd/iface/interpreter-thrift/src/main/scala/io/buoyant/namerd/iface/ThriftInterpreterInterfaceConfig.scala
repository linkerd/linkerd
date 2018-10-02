package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Namer, Path, Stack, ThriftMux}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param
import io.buoyant.namerd.iface.ThriftNamerInterface.LocalStamper
import io.buoyant.namerd._
import java.net.{InetAddress, InetSocketAddress}

import com.twitter.finagle.tracing.NullTracer

case class ThriftInterpreterInterfaceConfig(
  cache: Option[CapacityConfig] = None
) extends InterpreterInterfaceConfig {
  @JsonIgnore
  protected def defaultAddr = ThriftInterpreterInterfaceConfig.defaultAddr

  @JsonIgnore
  def mk(
    interpreters: Ns => NameInterpreter,
    namers: Map[Path, Namer],
    store: DtabStore,
    stats: StatsReceiver
  ): Servable = {
    val stats1 = stats.scope(ThriftInterpreterInterfaceConfig.kind)

    val iface = new ThriftNamerInterface(
      interpreters,
      namers,
      new LocalStamper,
      cache.map(_.capacity).getOrElse(ThriftNamerInterface.Capacity.default),
      stats1
    )
    val params =
      tlsParams +
        param.Stats(stats1) ++
        socketOptParams
    ThriftServable(addr, iface, params)
  }
}

object ThriftInterpreterInterfaceConfig {
  val kind = "io.l5d.thriftNameInterpreter"
  val defaultAddr = new InetSocketAddress(InetAddress.getLoopbackAddress, 4100)
}

class ThriftInterpreterInterfaceInitializer extends InterfaceInitializer {
  override val configId = ThriftInterpreterInterfaceConfig.kind
  val configClass = classOf[ThriftInterpreterInterfaceConfig]
}

case class ThriftServable(addr: InetSocketAddress, iface: AnyRef, params: Stack.Params) extends Servable {
  def kind = ThriftInterpreterInterfaceConfig.kind
  val thriftMux = ThriftMux.server
  def serve() = ThriftMux.server
    .withTracer(NullTracer).configuredParams(params).serveIface(addr, iface)
}

case class CapacityConfig(
  bindingCacheActive: Option[Int] = None,
  bindingCacheInactive: Option[Int] = None,
  bindingCacheInactiveTTLSecs: Option[Int] = None,
  addrCacheActive: Option[Int] = None,
  addrCacheInactive: Option[Int] = None,
  addrCacheInactiveTTLSecs: Option[Int] = None
) {
  private[this] val default = ThriftNamerInterface.Capacity.default

  def capacity = ThriftNamerInterface.Capacity(
    bindingCacheActive = bindingCacheActive.getOrElse(default.bindingCacheActive),
    bindingCacheInactive = bindingCacheInactive.getOrElse(default.bindingCacheInactive),
    bindingCacheInactiveTTLSecs = bindingCacheInactiveTTLSecs.getOrElse(default.bindingCacheInactiveTTLSecs),
    addrCacheActive = addrCacheActive.getOrElse(default.addrCacheActive),
    addrCacheInactive = addrCacheInactive.getOrElse(default.addrCacheInactive),
    addrCacheInactiveTTLSecs = addrCacheInactiveTTLSecs.getOrElse(default.addrCacheInactiveTTLSecs)
  )
}
