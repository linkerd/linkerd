package io.buoyant.namer

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.Activity

class TestInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[TestInterpreterConfig]
  override val configId = TestInterpreterConfig.kind
}

object TestInterpreterInitializer extends TestInterpreterInitializer

case class TestInterpreterConfig(alwaysFail: Option[Boolean]) extends InterpreterConfig {
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    TestInterpreter(alwaysFail getOrElse false)
  }
}

object TestInterpreterConfig {
  def kind = "test"
}

case class TestInterpreter(alwaysFail: Boolean) extends NameInterpreter {
  override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] =
    if (alwaysFail)
      Activity.value(NameTree.Neg)
    else
      Activity.pending
}
