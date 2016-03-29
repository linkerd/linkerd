package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.ThriftMux
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.scrooge.ThriftService
import com.twitter.util.Duration
import com.twitter.util.TimeConversions._
import io.buoyant.namerd.iface.ThriftNamerInterface.LocalStamper
import io.buoyant.namerd._
import java.net.InetSocketAddress
import scala.util.Random

case class ThriftInterpreterInterfaceConfig(
  retryBaseSecs: Option[Int] = None,
  retryJitterSecs: Option[Int] = None
) extends InterpreterInterfaceConfig {
  @JsonIgnore
  protected def defaultAddr = ThriftInterpreterInterfaceConfig.defaultAddr

  @JsonIgnore
  def mk(namers: Ns => NameInterpreter): Servable = {
    val retryIn: () => Duration = {
      val retry = retryBaseSecs.map(_.seconds).getOrElse(10.minutes)
      val jitter = retryJitterSecs.map(_.seconds).getOrElse(1.minute)
      () => retry + (Random.nextGaussian() * jitter.inSeconds).toInt.seconds
    }
    ThriftServable(addr, new ThriftNamerInterface(namers, new LocalStamper, retryIn))
  }
}

object ThriftInterpreterInterfaceConfig {
  val kind = "thriftNameInterpreter"
  val defaultAddr = new InetSocketAddress(4100)
}

class ThriftInterpreterInterfaceInitializer extends InterfaceInitializer {
  override val configId = ThriftInterpreterInterfaceConfig.kind
  val configClass = classOf[ThriftInterpreterInterfaceConfig]
}

case class ThriftServable(addr: InetSocketAddress, iface: ThriftService) extends Servable {
  def kind = ThriftInterpreterInterfaceConfig.kind
  def serve() = ThriftMux.serveIface(addr, iface)
}
