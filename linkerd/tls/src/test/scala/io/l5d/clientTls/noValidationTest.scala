package io.l5d.clientTls

import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.TlsClientInitializer
import org.scalatest.FunSuite

class noValidationTest extends FunSuite {
  test("sanity") {
    new noValidation().tlsClientPrep
  }

  test("service registration") {
    assert(LoadService[TlsClientInitializer].exists(_.isInstanceOf[NoValidationInitializer]))
  }
}
