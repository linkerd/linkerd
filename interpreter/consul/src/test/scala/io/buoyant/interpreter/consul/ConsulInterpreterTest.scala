package io.buoyant.interpreter.consul

import com.twitter.app.LoadService
import com.twitter.finagle.{Dtab, Path, Service}
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.consul.v1.KvApi
import io.buoyant.namer.{ConfiguredDtabNamer, InterpreterConfig, InterpreterInitializer}
import io.buoyant.test.FunSuite
import org.scalatest.Inside


class ConsulInterpreterTest extends FunSuite with Inside {

  val service = new Service[Request, Response] {
    val content = "/svc=>/#/io.l5d.consul/dc1;/svc/srv=>/$/inet/127.1/9991"
    override def apply(request: Request): Future[Response] = {
      val rep = Response()
      rep.contentString = content
      Future.value(rep)
    }
  }
  object TestConsulInterpreterConfig extends {
      val api = KvApi(service, Stream.empty)
  } with ConsulDtabInterpreterConfig(None, None, None, None)
  private[this] def parse(yaml: String): ConsulDtabInterpreterConfig =
    Parser.objectMapper(yaml, Iterable(Seq(ConsulInterpreterInitializer)))
      .readValue[InterpreterConfig](yaml)
      .asInstanceOf[ConsulDtabInterpreterConfig]

  val yaml = """
               |kind: io.l5d.consul.interpreter
               |host: consul
               |port: 8500
               |namespace: internal
             """.stripMargin

  test("interpreter registration") {
    assert(
      LoadService[InterpreterInitializer]()
        .exists(_.isInstanceOf[ConsulInterpreterInitializer])
    )
  }

  test("parse config"){
    val config = parse(yaml)
    inside(config) {
      case ConsulDtabInterpreterConfig(host, port, _, namespace, _, _, _, _, _, _, _) =>
        assert(host.get == "consul")
        assert(port.get == Port(8500))
        assert(namespace.get == "internal")
    }
  }

  test("admin handler configured"){
    val interpreter = TestConsulInterpreterConfig.newInterpreter(Params.empty)
    val handlers = interpreter.asInstanceOf[ConfiguredDtabNamer].handlers
    val mapper = Parser.jsonObjectMapper(Nil)
    val _ = interpreter.bind(Dtab.empty, Path.read("/svc")).run.changes.respond(_ => ())

    handlers.headOption match {
      case Some(h) =>
        val rep = await(h.service(Request("interpreter_state/io.l5d.consul.interpreter.json")))
        val json = mapper.readValue[Map[String, AnyRef]](rep.contentString)
        val watchState = json("state").asInstanceOf[Map[String, String]]
        assert(watchState("running").asInstanceOf[Boolean])
      case None => fail("consul interpreter handlers not configured")
    }
  }
}
