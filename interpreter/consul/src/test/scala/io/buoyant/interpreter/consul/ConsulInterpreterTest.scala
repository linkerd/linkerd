package io.buoyant.interpreter.consul

import com.twitter.app.LoadService
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}
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
    val yaml =
      s"""
     |kind: io.l5d.consul.interpreter
     |host: consul-node
     |port: 9999
     |namespace: internal
     """.stripMargin


    val config = parse(yaml)
    inside(config) {
      case ConsulDtabInterpreterConfig(host, port, _, namespace, _, _, _, _, _, _, _, _) =>
        assert(host.get == "consul-node")
        assert(port.get == Port(9999))
        assert(namespace.get == "internal")
    }
  }
}
