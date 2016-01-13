package io.buoyant.linkerd

import com.fasterxml.jackson.core.JsonParser
import com.twitter.finagle.{Addr, Name, NameTree, Namer, Path, Stack}
import com.twitter.util.{Activity, Var}

object TestNamer {
  case class Buh(buh: Boolean)
  implicit object Buh extends Stack.Param[Buh] {
    val default = Buh(false)
    val parser = Parsing.Param.Boolean("buh")(Buh(_))
  }

  val defaultParams = Stack.Params.empty +
    NamerInitializer.Prefix(Path.read("/foo"))
}

class TestNamer(val params: Stack.Params) extends NamerInitializer {
  def this() = this(TestNamer.defaultParams)
  def withParams(ps: Stack.Params) = new TestNamer(ps)

  def paramKeys = TestNamer.Buh.parser.keys
  def readParam(k: String, p: JsonParser) =
    withParams(TestNamer.Buh.parser.read(k, p, params))

  def newNamer() = new Namer {
    val buh = params[TestNamer.Buh].buh

    def lookup(path: Path): Activity[NameTree[Name]] = {
      val t = path match {
        case Path.Utf8("buh", _*) if !buh => NameTree.Neg
        case path =>
          val addr = Var.value(Addr.Pending)
          NameTree.Leaf(Name.Bound(addr, prefix, path))
      }
      Activity.value(t)
    }
  }
}
