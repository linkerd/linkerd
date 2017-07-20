package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.storage._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Namer, Path, Stack, Thrift, ThriftMux}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param
import com.twitter.finagle.thrift.Protocols
import com.twitter.scrooge.ThriftService
import com.twitter.util.Duration
import com.twitter.util.TimeConversions._
import io.buoyant.namerd.iface.ThriftNamerInterface.LocalStamper
import io.buoyant.namerd._
import java.net.InetSocketAddress
import scala.util.Random

case class ThriftInterpreterInterfaceConfig(
  retryBaseSecs: Option[Int] = None,
  retryJitterSecs: Option[Int] = None,
  cache: Option[CapacityConfig] = None,
  maxRequestSize: Option[Int] = None
) extends InterpreterInterfaceConfig {
  @JsonIgnore
  protected def defaultAddr = ThriftInterpreterInterfaceConfig.defaultAddr

  @JsonIgnore
  val readLength = maxRequestSize.getOrElse(1.megabyte.bytes.toInt)

  @JsonIgnore
  def mk(
    interpreters: Ns => NameInterpreter,
    namers: Map[Path, Namer],
    store: DtabStore,
    stats: StatsReceiver
  ): Servable = {
    val stats1 = stats.scope(ThriftInterpreterInterfaceConfig.kind)

    val retryIn: () => Duration = {
      val retry = retryBaseSecs.map(_.seconds).getOrElse(10.minutes)
      val jitter = retryJitterSecs.map(_.seconds).getOrElse(1.minute)
      () => retry + (Random.nextGaussian() * jitter.inSeconds).toInt.seconds
    }
    val iface = new ThriftNamerInterface(
      interpreters,
      namers,
      new LocalStamper,
      retryIn,
      cache.map(_.capacity).getOrElse(ThriftNamerInterface.Capacity.default),
      stats1
    )
    val params =
      tlsParams +
        param.Stats(stats1) +
        Thrift.ThriftImpl.Netty4
    val server = ThriftMux.server.withParams(ThriftMux.server.params ++ params)
      .withProtocolFactory(Protocols.binaryFactory(readLength = readLength))
    ThriftServable(addr, iface, server)
  }
}

object ThriftInterpreterInterfaceConfig {
  val kind = "io.l5d.thriftNameInterpreter"
  val defaultAddr = new InetSocketAddress(4100)
}

class ThriftInterpreterInterfaceInitializer extends InterfaceInitializer {
  override val configId = ThriftInterpreterInterfaceConfig.kind
  val configClass = classOf[ThriftInterpreterInterfaceConfig]
}

case class ThriftServable(addr: InetSocketAddress, iface: ThriftService, server: ThriftMux.Server) extends Servable {
  def kind = ThriftInterpreterInterfaceConfig.kind
  def serve() = server.serveIface(addr, iface)
}

case class CapacityConfig(
  bindingCacheActive: Option[Int] = None,
  bindingCacheInactive: Option[Int] = None,
  addrCacheActive: Option[Int] = None,
  addrCacheInactive: Option[Int] = None
) {
  private[this] val default = ThriftNamerInterface.Capacity.default

  def capacity = ThriftNamerInterface.Capacity(
    bindingCacheActive = bindingCacheActive.getOrElse(default.bindingCacheActive),
    bindingCacheInactive = bindingCacheInactive.getOrElse(default.bindingCacheInactive),
    addrCacheActive = addrCacheActive.getOrElse(default.addrCacheActive),
    addrCacheInactive = addrCacheInactive.getOrElse(default.addrCacheInactive)
  )
}
