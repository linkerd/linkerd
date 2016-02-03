package io.buoyant.linkerd

import com.twitter.finagle.{Dtab, Name, NameTree, Namer, Path, Stack}
import org.scalatest.FunSuite

class booNamer extends TestNamer(
  TestNamer.defaultParams + NamerInitializer.Prefix(Path.read("/boo"))
)

class booUrnsNamer extends TestNamer(
  TestNamer.defaultParams + NamerInitializer.Prefix(Path.read("/boo/urns"))
)

class NamerInitializersTest extends FunSuite {
  val namers = NamerInitializers(new booNamer, new booUrnsNamer)

  test("namers evaluated bottom-up") {
    val path = Path.read("/boo/urns")

    val booYaml =
      """|- kind: io.buoyant.linkerd.booUrnsNamer
         |- kind: io.buoyant.linkerd.booNamer
         |""".stripMargin
    namers.read(Yaml(booYaml)).bind(Dtab.empty, path).sample() match {
      case NameTree.Leaf(bound: Name.Bound) =>
        assert(bound.id == Path.read("/boo"))
        assert(bound.path == Path.read("/urns"))
      case tree => fail(s"unexpected result: $tree")
    }

    val booUrnsYaml =
      """|- kind: io.buoyant.linkerd.booNamer
         |- kind: io.buoyant.linkerd.booUrnsNamer
         |""".stripMargin
    namers.read(Yaml(booUrnsYaml)).bind(Dtab.empty, path).sample() match {
      case NameTree.Leaf(bound: Name.Bound) =>
        assert(bound.id == Path.read("/boo/urns"))
        assert(bound.path == Path.empty)
      case tree => fail(s"unexpected result: $tree")
    }
  }
}
