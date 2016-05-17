package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.IdentifierInitializer
import org.scalatest.FunSuite

class MethodAndHostIdentifierTest extends FunSuite {
  test("sanity") {
    val _ = new MethodAndHostIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[MethodAndHostIdentifierInitializer]))
  }
}
