package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.{DefaultHttpIdentifierConfig, DefaultHttpIdentifierInitializer}
import org.scalatest.FunSuite

class DefaultHttpIdentifierTest extends FunSuite {
  test("sanity") {
    val _ = new DefaultHttpIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[DefaultHttpIdentifierInitializer]))
  }
}
