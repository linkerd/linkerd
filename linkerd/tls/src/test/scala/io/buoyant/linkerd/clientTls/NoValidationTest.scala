package io.buoyant.linkerd.clientTls

import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.TlsClientInitializer
import org.scalatest.FunSuite

class NoValidationTest extends FunSuite {
  test("sanity") {
    new NoValidationConfig().tlsClientPrep
  }

  test("service registration") {
    assert(LoadService[TlsClientInitializer].exists(_.isInstanceOf[NoValidationInitializer]))
  }
}
