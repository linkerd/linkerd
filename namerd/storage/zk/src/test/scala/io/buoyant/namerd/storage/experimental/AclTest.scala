package io.buoyant.namerd.storage.experimental

import org.scalatest.FunSuite

class AclTest extends FunSuite {

  def assertThrows[T <: AnyRef: Manifest](f: => Any): Unit = {
    val _ = intercept[T](f)
  }

  test("unrecognized perm") {
    assertThrows[IllegalArgumentException](Acl("scheme", "id", "crwdaz"))
  }

  test("duplicate perm") {
    assertThrows[IllegalArgumentException](Acl("scheme", "id", "cc"))
  }
}
