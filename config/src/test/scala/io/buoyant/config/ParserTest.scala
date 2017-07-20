package io.buoyant.config

import io.buoyant.test.FunSuite
import com.fasterxml.jackson.core.JsonParseException

class ParserTest extends FunSuite {

  abstract class SubConfig extends PolymorphicConfig

  class AConfig extends PolymorphicConfig
  class ASubConfig extends SubConfig
  class BConfig extends PolymorphicConfig

  class A1 extends ConfigInitializer {
    val configClass = classOf[AConfig]
    override val configId = "io.l5d.a"
  }

  class A2 extends ConfigInitializer {
    val configClass = classOf[ASubConfig]
    override val configId = "io.l5d.a"
  }

  class B extends ConfigInitializer {
    val configClass = classOf[BConfig]
    override val configId = "io.l5d.b"
  }

  test("allows different ids for the same parent config class") {
    val _ = Parser.objectMapper("{}", Seq(Seq(new A1(), new B())))
  }

  test("disallows same id for same parent config class") {
    assertThrows[ConflictingSubtypes] {
      val _ = Parser.objectMapper("{}", Seq(Seq(new A1(), new A1())))
    }
  }

  test("allows same id for different parent config classes") {
    val _ = Parser.objectMapper("{}", Seq(Seq(new A1(), new A2())))
  }

  test("allows same id for different initializers") {
    val _ = Parser.objectMapper("{}", Seq(Seq(new A1()), Seq(new A1())))
  }

  test("errors on duplicated properties") {

    val mapper = Parser.objectMapper("{}", Nil)
    assertThrows[JsonParseException] {
      val foo = mapper.readValue[Foo]("""{"a": 5, "a": 7}""")
    }
  }
}

case class Foo(a: Int)