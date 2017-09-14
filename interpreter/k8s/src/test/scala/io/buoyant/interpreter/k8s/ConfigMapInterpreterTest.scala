package io.buoyant.interpreter.k8s

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Dtab, Service}
import com.twitter.finagle.util.LoadService
import com.twitter.io.Writer
import com.twitter.util.{Promise, Return}
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.k8s.v1
import io.buoyant.k8s.v1.ConfigMap
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}
import io.buoyant.test.FunSuite
import org.scalatest.{Inside, OptionValues, TryValues}

class ConfigMapInterpreterTest extends FunSuite
  with Inside
  with TryValues {

  trait Fixtures {
    @volatile var writer: Writer = null

    val service = Service.mk[Request, Response] {
      ???
    }

    val api = v1.Api(service)
  }

  test("interpreter registration") {
    assert(LoadService[InterpreterInitializer]()
      .exists(_.isInstanceOf[ConfigMapInterpreterInitializer]))
  }

  private[this] def parse(yaml: String): ConfigMapInterpreterConfig =
    Parser.objectMapper(yaml, Iterable(Seq(ConfigMapInterpreterInitializer)))
      .readValue[InterpreterConfig](yaml)
      .asInstanceOf[ConfigMapInterpreterConfig]

  test("parse config") {
    val yaml =
      s"""|kind: io.l5d.k8s.configMap
          |host: "foo"
          |port: 8888
          |namespace: "my-great-namespace"
          |name: "configMap"
          |filename: "test.dtab"
          |""".stripMargin
    val config = parse(yaml)
    inside(config) {
      case ConfigMapInterpreterConfig(host, port, namespace, name, filename, _) =>
        assert(host.contains("foo"))
        assert(port.contains(Port(8888)))
        assert(namespace.contains("my-great-namespace"))
        assert(name == "configMap")
        assert(filename == "test.dtab")
    }
  }

  test("get empty dtab") {
    val yaml =
      s"""|kind: io.l5d.k8s.configMap
          |name: "configMap"
          |filename: "test.dtab"
          |""".stripMargin
    val config = parse(yaml)
    val configMap = ConfigMap(Map[String, String]())
    assert(config.getDtab(configMap).isEmpty)
  }

  test("get non-empty dtab") {
    val yaml =
      s"""|kind: io.l5d.k8s.configMap
          |name: "configMap"
          |filename: "test.dtab"
          |""".stripMargin
    val config = parse(yaml)
    val dtab = "/foo => /bar/baz;"
    val configMap = ConfigMap(Map(
      "test.dtab" -> dtab,
      "otherTest.dtab" -> "quux => quuux"
    ))
    assert(config.getDtab(configMap) == Return(Dtab.read(dtab)))
  }


}
