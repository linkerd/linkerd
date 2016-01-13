package io.buoyant.linkerd

import com.twitter.finagle.{Dtab, NameTree, Path}
import org.scalatest.FunSuite

class NamerInitializerTest extends FunSuite {

  for {
    (lang, namer) <- Seq("scala" -> new TestNamer, "java" -> new JTestNamer)
  } {
    val namers = NamerInitializers(namer)
    val kind = namer.getClass.getCanonicalName

    test(s"parse simple namer ($lang)") {
      val yaml =
        s"""
- kind: $kind
"""
      val parsed = namers.read(Yaml(yaml))
      val NameTree.Leaf(foo) = parsed.bind(Dtab.empty, Path.read("/foo/bar")).sample()
      assert(foo.id == Path.read("/foo"))
      assert(foo.path == Path.read("/bar"))

      assert(parsed.bind(Dtab.empty, Path.read("/foo/buh")).sample() == NameTree.Neg)
    }

    test(s"parse namer under another prefix ($lang)") {
      val yaml =
        s"""
- kind: $kind
  prefix: /fuh
  buh: true
"""
      val parsed = namers.read(Yaml(yaml))
      val NameTree.Leaf(buh) = parsed.bind(Dtab.empty, Path.read("/fuh/buh")).sample()
      assert(buh.id == Path.read("/fuh"))
      assert(buh.path == Path.read("/buh"))
    }

    test(s"falls back across NameTree.Neg ($lang)") {
      val yaml =
        s"""
- kind: $kind
- kind: $kind
  buh: true
"""
      val parsed = namers.read(Yaml(yaml))
      val NameTree.Leaf(buh) = parsed.bind(Dtab.empty, Path.read("/foo/buh")).sample()
      assert(buh.id == Path.read("/foo"))
      assert(buh.path == Path.read("/buh"))
    }
  }
}
