package io.buoyant.namer.zk

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.namer.NamerInitializer
import org.scalatest.FunSuite

class ZkLeaderTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = ZkLeaderNamerConfig("localhost:1234").newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[ZkLeaderNamerInitializer]))
  }
}
