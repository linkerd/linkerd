package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{BufferingTracer, NullTracer}
import com.twitter.finagle.util.LoadService
import com.twitter.util._
import io.buoyant.config.Parser
import org.scalatest._

class TelemeterInitializerTest extends FunSuite {

  test("telemetry is totally radical") {
    val yaml =
      """|kind: io.l5d.testTelemeter
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    val closer = telemeter.run()
  }
}

class TestTelemeterInitializer extends TelemeterInitializer {
  type Config = TestTelemeterConfig
  override def configId = "io.l5d.testTelemeter"
  def configClass = classOf[TestTelemeterConfig]
}

case class TestTelemeterConfig(metrics: Boolean, tracing: Boolean) extends TelemeterConfig {
  def mk(params: Stack.Params): TestTelemeter = new TestTelemeter(metrics, tracing)
}

case class TestTelemeter(metrics: Boolean, tracing: Boolean) extends Telemeter {
  val stats =
    if (metrics) new InMemoryStatsReceiver
    else NullStatsReceiver

  val tracer =
    if (tracing) new BufferingTracer
    else NullTracer

  val adminRoutes = Seq.empty

  def run(): Closable with Awaitable[Unit] =
    new Closable with CloseAwaitably {
      def close(d: Time) = closeAwaitably(Future.Unit)
    }
}
