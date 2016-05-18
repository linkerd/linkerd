package io.buoyant.namer.zk

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.types.{HostAndPort, Port}
import io.buoyant.namer.NamerInitializer
import org.scalatest.FunSuite

class ZkLeaderTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = ZkLeaderNamerConfig(Seq(HostAndPort(Some("localhost"), Some(Port(1234)))))
      .newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[ZkLeaderNamerInitializer]))
  }
}
