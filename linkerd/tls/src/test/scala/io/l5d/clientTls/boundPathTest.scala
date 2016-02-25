package io.l5d.clientTls

import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.TlsClientInitializer
import org.scalatest.FunSuite

class boundPathTest extends FunSuite {
  test("sanity") {
    static("hello", None).tlsClientPrep
  }

  test("service registration") {
    assert(LoadService[TlsClientInitializer].exists(_.isInstanceOf[BoundPathInitializer]))
  }
}
