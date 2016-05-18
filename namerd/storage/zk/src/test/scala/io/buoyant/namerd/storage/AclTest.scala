package io.buoyant.namerd.storage

import io.buoyant.test.Exceptions
import org.scalatest.FunSuite

class AclTest extends FunSuite with Exceptions {

  test("unrecognized perm") {
    assertThrows[IllegalArgumentException](Acl("scheme", "id", "crwdaz"))
  }

  test("duplicate perm") {
    assertThrows[IllegalArgumentException](Acl("scheme", "id", "cc"))
  }
}
