package io.buoyant.interpreter.k8s

import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namer.InterpreterConfig
import io.buoyant.test.FunSuite
import org.scalatest.{Inside, OptionValues}

class ConfigMapInterpreterTest extends FunSuite
  with Inside {

  test("parse config") {
    val yaml =
      s"""|kind: io.l5d.k8s.configMap
          |host: "foo"
          |port: 8888
          |namespace: "my-great-namespace"
          |name: "configMap"
          |filename: "fileName"
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConfigMapInterpreterInitializer)))
    val config = mapper.readValue[InterpreterConfig](yaml).asInstanceOf[ConfigMapInterpreterConfig]
    inside(config) {
      case ConfigMapInterpreterConfig(host, port, namespace, name, filename) =>
        assert(host.contains("foo"))
        assert(port.contains(Port(8888)))
        assert(namespace.contains("my-great-namespace"))
        assert(name == "configMap")
        assert(filename == "fileName")
    }
  }
}
