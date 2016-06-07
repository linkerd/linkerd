package io.buoyant.namer.zk

import com.twitter.finagle.{Path, Stack}
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

  test("parse zk hosts") {
    val namer = new leader()
      .zkLeaderNamer(Path.read("/1.2.3.4:8000::5.6.7.8:8001/path/to/group"))
    assert(namer.zkAddrs ==
      Seq(
        HostAndPort(Some("1.2.3.4"), Some(Port(8000))),
        HostAndPort(Some("5.6.7.8"), Some(Port(8001)))
      ))
  }
}
