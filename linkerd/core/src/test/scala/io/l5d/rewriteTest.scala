package io.l5d

import com.twitter.finagle.{Addr, Dtab, NameTree, Name, Path}
import com.twitter.util.Var
import io.buoyant.linkerd.{NamerInitializers, Parsing, Yaml}
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class rewriteTest extends FunSuite with Awaits {

  val namers = NamerInitializers(new io.l5d.rewrite)

  test("supports rewrites") {
    val yaml =
      """|kind: io.l5d.rewrite
         |prefix: /http/1.1
         |dst: /host/$2
         |""".stripMargin
    val init = namers.readNamer(Yaml(yaml))
    assert(init.prefix == Path.read("/http/1.1"))
    val namer = init.newNamer()
    val path = Path.read("/DELETE/hi/there")
    val tree = namer.lookup(path).sample()
    assert(tree == NameTree.Leaf(Name.Path(Path.read("/host/hi"))))
  }

  test("neg when out of range") {
    val yaml =
      """|kind: io.l5d.rewrite
         |prefix: /http/1.1
         |dst: /host/$10
         |""".stripMargin
    val init = namers.readNamer(Yaml(yaml))
    assert(init.prefix == Path.read("/http/1.1"))
    val namer = init.newNamer()
    val path = Path.read("/DELETE/hi/there")
    val tree = namer.lookup(path).sample()
    assert(tree == NameTree.Neg)
  }

  test("errors when dst is not a path") {
    val yaml =
      """|kind: io.l5d.rewrite
         |prefix: /http/1.1
         |dst: host/$1
         |""".stripMargin
    val init = namers.readNamer(Yaml(yaml))
    val err = intercept[IllegalArgumentException] { init.newNamer() }
    assert(err.getMessage == "io.l5d.rewrite requires that 'dst' is a path starting with '/'")
  }

}
