package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.util.{Activity, Var}

class TestNamerInitializer extends NamerInitializer {
  val configClass = classOf[TestNamerConfig]
  override val configId = "test"
}

object TestNamerInitializer extends TestNamerInitializer

class TestNamerConfig extends NamerConfig { config =>
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/foo")

  var buh: Option[Boolean] = None

  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer =
    new TestNamer(config.buh.getOrElse(false), prefix)
}

class TestNamer(buh: Boolean, prefix: Path) extends Namer {

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

class ConflictingNamerInitializer extends NamerInitializer {
  val configClass = classOf[ConflictingNamer]
  override val configId = TestNamerInitializer.configId
}

object ConflictingNamerInitializer extends ConflictingNamerInitializer

class ConflictingNamer extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = ???
  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = ???
}

class ErrorNamerInitializer extends NamerInitializer {
  val configClass = classOf[ErrorNamerConfig]
  override val configId = "error"
}

object ErrorNamerInitializer extends ErrorNamerInitializer

case class TestNamingError(path: Path) extends Throwable(s"error naming ${path.show}") {
  override def toString = s"TestNamingError(${path.show})"
}

class ErrorNamerConfig extends NamerConfig { config =>
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/error")

  @JsonIgnore
  override def newNamer(params: Stack.Params) = new Namer {
    def lookup(path: Path) = Activity.exception(TestNamingError(prefix ++ path))
  }
}
