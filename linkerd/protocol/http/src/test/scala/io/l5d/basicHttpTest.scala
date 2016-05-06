package io.l5d

import com.twitter.finagle.Path
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.IdentifierInitializer
import io.l5d.identifier.{DefaultHttpIdentifierInitializer, DefaultHttpIdentifierConfig}
import org.scalatest.FunSuite

class basicHttpTest extends FunSuite {
  test("sanity") {
    val _ = new DefaultHttpIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[DefaultHttpIdentifierInitializer]))
  }
}
