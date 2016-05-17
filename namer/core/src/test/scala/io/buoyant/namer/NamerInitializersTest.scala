package io.buoyant.namer

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.config.Parser
import org.scalatest.FunSuite

class NamerInitializersTest extends FunSuite {

  def interpreter(config: String): NameInterpreter = {
    val mapper = Parser.objectMapper(config, Iterable(Seq(booNamerInitializer, booUrnsNamerInitializer)))
    val cfg = mapper.readValue[Seq[NamerConfig]](config)
    ConfiguredNamersInterpreter(cfg.reverse.map { c =>
      c.prefix -> c.newNamer(Stack.Params.empty)
    })
  }

  test("namers evaluated bottom-up") {
    val path = Path.read("/#/boo/urns")

    val booYaml =
      """|- kind: io.buoyant.namer.booUrnsNamer
         |- kind: io.buoyant.namer.booNamer
         |""".stripMargin
    interpreter(booYaml).bind(Dtab.empty, path).sample() match {
      case NameTree.Leaf(bound) =>
        assert(bound.id == Path.read("/#/boo"))
        assert(bound.path == Path.read("/urns"))
      case tree => fail(s"unexpected result: $tree")
    }

    val booUrnsYaml =
      """|- kind: io.buoyant.namer.booNamer
         |- kind: io.buoyant.namer.booUrnsNamer
         |""".stripMargin

    interpreter(booUrnsYaml).bind(Dtab.empty, path).sample() match {
      case NameTree.Leaf(bound: Name.Bound) =>
        assert(bound.id == Path.read("/#/boo/urns"))
        assert(bound.path == Path.empty)
      case tree => fail(s"unexpected result: $tree")
    }
  }
}
