package io.buoyant.namerd

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Path, Namer, NullServer}
import com.twitter.finagle.naming.NameInterpreter
import java.net.InetSocketAddress

class TestInterpreterInterfaceConfig extends InterpreterInterfaceConfig {
  val defaultAddr: InetSocketAddress = new InetSocketAddress(0)

  def mk(
    interpreters: Ns => NameInterpreter,
    namers: Map[Path, Namer],
    store: DtabStore,
    stats: StatsReceiver
  ): Servable =
    TestNamerInterfaceServable
}

object TestNamerInterfaceServable extends Servable {
  def kind = "test"
  def serve() = NullServer
}

case class TestNamerInterface(namers: Ns => NameInterpreter)

class TestNamerInterfaceInitializer extends InterfaceInitializer {
  override def configId = "test"
  override def configClass = classOf[TestInterpreterInterfaceConfig]
}

object TestNamerInterfaceInitializer extends TestNamerInterfaceInitializer
