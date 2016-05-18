package io.buoyant.linkerd.clientTls

import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.TlsClientInitializer
import org.scalatest.FunSuite

class StaticTest extends FunSuite {
  test("sanity") {
    StaticConfig("hello", None).tlsClientPrep
  }

  test("service registration") {
    assert(LoadService[TlsClientInitializer].exists(_.isInstanceOf[StaticInitializer]))
  }
}
