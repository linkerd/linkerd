package io.buoyant.linkerd.namer.http

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import org.scalatest.FunSuite

class HttpNamerTest extends FunSuite {

  test("drop none") {
    val namer = new HttpNamer(Path.Utf8("dst"))
    val orig = Path.Utf8("1.1", "GET", "animals", "dogs")
    val name = namer.lookup(orig).sample()
    assert(name == NameTree.Leaf(Name.Path(Path.Utf8("dst") ++ orig)))
  }

  test("drop all") {
    val namer = new HttpNamer(Path.Utf8("dst"), true, true, true)
    val orig = Path.Utf8("1.1", "GET", "animals", "dogs")
    val name = namer.lookup(orig).sample()
    assert(name == NameTree.Leaf(Name.Path(Path.Utf8("dst", "dogs"))))
  }
}
