package io.buoyant.use

import io.buoyant.gen.Gen
import scala.language.reflectiveCalls

object Use {

  def main(args: Array[String]): Unit = {
    val idl = Gen.fromIdl("gen/Foo.idl")

    val foo = idl.Foo(x = 1, y = "two", hello = List(1, 2, 3))

    println(foo)
  }
}
