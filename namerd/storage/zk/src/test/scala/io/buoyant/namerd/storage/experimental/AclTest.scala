package io.buoyant.namerd.storage.experimental

import org.scalatest.FunSuite

class AclTest extends FunSuite {
  test("unrecognized perm") {
    intercept[IllegalArgumentException](Acl("scheme", "id", "crwdaz"))
  }

  test("duplicate perm") {
    intercept[IllegalArgumentException](Acl("scheme", "id", "cc"))
  }
}
