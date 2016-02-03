package io.l5d

import com.twitter.finagle.{Addr, Dtab, NameTree, Name, Path}
import com.twitter.util.Var
import io.buoyant.linkerd.{NamerInitializers, Yaml}
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class rewriteTest extends FunSuite with Awaits {

  val namers = NamerInitializers(new io.l5d.rewrite)

  test("supports rewrites") {
    val yaml =
      """|- kind: io.l5d.rewrite
         |  prefix: /http/1.1
         |  dst: /host/$2
         |""".stripMargin
    val namer = namers.read(Yaml(yaml))
    val path = Path.read("/http/1.1/DELETE/hi/there")
    val dtab = Dtab.read("/host/hi => /$/inet/127.1/1234 ; /host/there => /$/inet/127.1/2345")
    val tree = namer.bind(dtab, path).sample()

    assert(tree == NameTree.Leaf(Name.Bound(
      Var.value(Addr.Bound(new InetSocketAddress("127.1", 1234))),
      Path.read("/$/inet/127.1/1234")
    )))
  }

  test("neg when out of range") {
    val yaml =
      """|- kind: io.l5d.rewrite
         |  prefix: /http/1.1
         |  dst: /host/$10
         |""".stripMargin
    val namer = namers.read(Yaml(yaml))
    val path = Path.read("/http/1.1/DELETE/hi/there")
    val dtab = Dtab.read("/host/hi => /$/inet/127.1/1234 ; /host/there => /$/inet/127.1/2345")
    val tree = namer.bind(dtab, path).sample()
    assert(tree == NameTree.Neg)
  }

  test("errors when dst is not a path") {
    val yaml =
      """|- kind: io.l5d.rewrite
         |  prefix: /http/1.1
         |  dst: host/$1
         |""".stripMargin
    intercept[IllegalArgumentException] {
      namers.read(Yaml(yaml))
    }
  }

}
