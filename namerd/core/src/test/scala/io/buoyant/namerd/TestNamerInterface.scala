package io.buoyant.namerd

import com.twitter.finagle.NullServer
import com.twitter.finagle.naming.NameInterpreter
import java.net.InetSocketAddress

class TestInterpreterInterfaceConfig extends InterpreterInterfaceConfig {
  val defaultAddr: InetSocketAddress = new InetSocketAddress(0)

  def mk(namers: Ns => NameInterpreter): Servable = TestNamerInterfaceServable
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
