package io.buoyant.linkerd

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.twitter.finagle.tracing.Tracer
import com.twitter.finagle.{Namer, Path, Stack}
import com.twitter.util.Duration
import io.buoyant.admin.{Admin, AdminConfig}
import io.buoyant.linkerd.MainTest.{MockLinker, RecordingTerminationHook}
import io.buoyant.telemetry.Telemeter
import io.buoyant.test.FunSuite
import org.scalatest.Matchers
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

import scala.concurrent.ExecutionContext.Implicits.global

class MainTest extends FunSuite with Eventually with Matchers with IntegrationPatience {

  test("should use the default grace period when shutting down the system") {
    val config = new Linker.LinkerConfig(None, Nil, None, Some(AdminConfig()), None) {
      override def mk(): Linker = MockLinker
    }

    val recordingTH = RecordingTerminationHook()
    scala.concurrent.Future {
      Main.start(config)(recordingTH)
    }

    eventually {
      recordingTH.shutdownGrace shouldBe Duration.fromSeconds(10)
    }

  }

  test("should use the configured grace period when shutting the system") {
    val adminConfig = AdminConfig(shutdownGraceMs = Some(5))
    val config = new Linker.LinkerConfig(None, Nil, None, Some(adminConfig), None) {
      override def mk(): Linker = MockLinker
    }

    val recordingTH = RecordingTerminationHook()
    scala.concurrent.Future {
      Main.start(config)(recordingTH)
    }

    eventually {
      recordingTH.shutdownGrace shouldBe Duration.fromMilliseconds(5)
    }

  }

}

object MainTest {

  case class RecordingTerminationHook() extends TerminationHook {
    private val shutdownGraceRef = new AtomicReference[Duration]()
    override def register(shutdown: Duration => Unit, shutdownGrace: Duration) =
      shutdownGraceRef.set(shutdownGrace)
    def shutdownGrace = shutdownGraceRef.get()
  }

  object MockLinker extends Linker {
    override def routers: Seq[Router] = Nil

    override def namers: Seq[(Path, Namer)] = Nil

    override def admin: Admin = new Admin(new InetSocketAddress(0), None)

    override def telemeters: Seq[Telemeter] = Nil

    override def tracer: Tracer = ???

    override def configured[T: Stack.Param](t: T): Linker = ???

  }

}
