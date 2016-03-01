package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.util.{Activity, Var}

class TestNamerInitializer extends NamerInitializer {
  val configClass = classOf[TestNamer]
}

object TestNamerInitializer extends TestNamerInitializer

class TestNamer extends NamerConfig { config =>
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/foo")

  var buh: Option[Boolean] = None

  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = new Namer {

    val buh = config.buh.getOrElse(false)

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

class TestInterpreter extends NamingFactoryConfig { config =>

  var buh: Option[Boolean] = None

  private[this] val interpreter = new naming.NameInterpreter {
    val buh = config.buh.getOrElse(false)
    def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
      val t = path match {
        case Path.Utf8(_, "buh", _*) if !buh => NameTree.Neg
        case Path.Utf8(id, rest@_*) =>
          val addr = Var.value(Addr.Pending)
          NameTree.Leaf(Name.Bound(addr, Path.Utf8(id), Path.Utf8(rest: _*)))
      }
      Activity.value(t)
    }
  }

  def newFactory(params: Stack.Params): NamingFactory =
    NamingFactory.Interpreter(kind, () => interpreter)
}

class TestInterpreterInitializer extends NamerInitializer {
  val configClass = classOf[TestInterpreter]
}

object TestInterpreterInitializer extends TestInterpreterInitializer
