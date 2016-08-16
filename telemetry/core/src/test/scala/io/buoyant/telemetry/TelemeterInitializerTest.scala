package io.buoyant.telemetry

import com.twitter.finagle.util.LoadService
import com.twitter.util._
import io.buoyant.config.Parser
import org.scalatest._

class TelemeterInitializerTest extends FunSuite {

  test("telemetry is totally radical") {
    val yaml =
      """|kind: io.l5d.testelemeter
         |radical: true
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk()
    val closer = telemeter.run()
  }
}

class TestTelemeterInitializer extends TelemeterInitializer {
  type Config = TestTelemeterConfig
  override def configId = "io.l5d.testelemeter"
  def configClass = classOf[TestTelemeterConfig]
}

case class TestTelemeterConfig(radical: Boolean) extends TelemeterConfig {
  def mk(): TestTelemeter = new TestTelemeter(radical)
}

case class TestTelemeter(radical: Boolean) extends Telemeter {
  @volatile var closed = false
  def run(): Closable with Awaitable[Unit] = new Closable with CloseAwaitably {
    def close(d: Time) = closeAwaitably {
      closed = true
      Future.Unit
    }
  }
}
