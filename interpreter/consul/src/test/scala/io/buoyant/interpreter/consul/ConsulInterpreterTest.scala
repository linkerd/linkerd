package io.buoyant.interpreter.consul

import com.twitter.app.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}
import io.buoyant.test.FunSuite
import org.scalatest.Inside

class ConsulInterpreterTest extends FunSuite with Inside {

  private[this] def parse(yaml: String): ConsulDtabInterpreterConfig =
    Parser.objectMapper(yaml, Iterable(Seq(ConsulInterpreterInitializer)))
      .readValue[InterpreterConfig](yaml)
      .asInstanceOf[ConsulDtabInterpreterConfig]

  test("interpreter registration") {
    assert(
      LoadService[InterpreterInitializer]()
        .exists(_.isInstanceOf[ConsulInterpreterInitializer])
    )
  }

  test("parse config"){
    val yaml = """
                 |kind: io.l5d.consul.interpreter
                 |host: consul
                 |port: 8500
                 |namespace: internal
               """.stripMargin

    val config = parse(yaml)
    inside(config) {
      case ConsulDtabInterpreterConfig(host, port, _, namespace, _, _, _, _, _, _, _) =>
        assert(host.get == "consul")
        assert(port.get == Port(8500))
        assert(namespace.get == "internal")
    }
  }
}
