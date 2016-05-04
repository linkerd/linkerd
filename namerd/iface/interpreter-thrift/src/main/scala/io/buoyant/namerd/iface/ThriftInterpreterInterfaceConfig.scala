package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Namer, ThriftMux}
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
  def mk(interpreters: Ns => NameInterpreter, namers: Map[Path, Namer], store: DtabStore): Servable = {
    val retryIn: () => Duration = {
      val retry = retryBaseSecs.map(_.seconds).getOrElse(10.minutes)
      val jitter = retryJitterSecs.map(_.seconds).getOrElse(1.minute)
      () => retry + (Random.nextGaussian() * jitter.inSeconds).toInt.seconds
    }
    val iface = new ThriftNamerInterface(interpreters, namers, new LocalStamper, retryIn)
    ThriftServable(addr, iface)
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
  def serve() = ThriftMux.server.serveIface(addr, iface)
}
