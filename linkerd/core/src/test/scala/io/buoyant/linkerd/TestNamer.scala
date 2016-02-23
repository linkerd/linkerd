package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.util.{Activity, Var}
import io.buoyant.linkerd.config.Parser

class TestNamer extends NamerInitializer {
  val configClass = classOf[TestNamerConfig]
  val configId = "io.buoyant.linkerd.TestNamer"
}

object TestNamer extends TestNamer

class TestNamerConfig extends NamerConfig { config =>
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
