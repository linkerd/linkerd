package io.buoyant.linkerd

import com.twitter.finagle.{Path, Dtab, NameTree, Stack}
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.linkerd.config.Parser
import org.scalatest.FunSuite

class InterpreterInitializerTest extends FunSuite {

  def parse(config: String): NameInterpreter = {
    val mapper = Parser.objectMapper(config, Iterable(Seq(TestInterpreterInitializer)))
    val interpCfg = mapper.readValue[InterpreterConfig](config)
    interpCfg.newInterpreter(Stack.Params.empty)
  }

  test("parse and initialize") {
    val yaml =
      s"""kind: test
         |alwaysFail: true
         |""".stripMargin

    val interpreter = parse(yaml)
    val activity = interpreter.bind(Dtab.read("/foo => /bar"), Path.read("/foo"))
    assert(activity.sample == NameTree.Neg) // since alwaysFail is true
  }
}
