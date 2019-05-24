package io.buoyant.interpreter

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.H2HeaderInjector
import com.twitter.finagle.buoyant.{H2, TlsClientConfig}
import com.twitter.finagle.liveness.FailureDetector
import com.twitter.finagle.liveness.FailureDetector.ThresholdConfig
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import io.buoyant.interpreter.mesh.Client
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}
import java.util.UUID.randomUUID
import scala.util.control.NoStackTrace

/**
 * The namerd interpreter offloads the responsibilities of name
 * resolution to the namerd service via the namerd streaming gRPC API.
 * Any namers configured in this linkerd are not used.
 */
class MeshInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[MeshInterpreterConfig]
  override def configId: String = "io.l5d.mesh"
}

object MeshInterpreterInitializer extends MeshInterpreterInitializer

object MeshInterpreterConfig {
  private val log = Logger.get(getClass.getName)

  val DefaultRoot = Path.Utf8("default")

  val defaultRetry = Retry(1, 10.minutes.inSeconds)
}

object FailureThresholdConfig {
  val DefaultMinPeriod = 5.seconds
  val DefaultCloseTimeout = 6.seconds
}

case class Retry(
  baseSeconds: Int,
  maxSeconds: Int
)

case class FailureThresholdConfig(
  minPeriodMs: Option[Int],
  closeTimeoutMs: Option[Int]
) {
  @JsonIgnore
  def params: Stack.Params = {
    val thresholdConfig = ThresholdConfig(
      minPeriodMs.map(_.milliseconds).getOrElse(FailureThresholdConfig.DefaultMinPeriod),
      closeTimeoutMs.map(_.milliseconds).getOrElse(FailureThresholdConfig.DefaultCloseTimeout)
    )
    StackParams.empty + FailureDetector.Param(thresholdConfig)
  }

}

case class MeshInterpreterConfig(
  dst: Option[Path],
  root: Option[Path],
  tls: Option[TlsClientConfig],
  retry: Option[Retry],
  failureThreshold: Option[FailureThresholdConfig]
) extends InterpreterConfig {
  import MeshInterpreterConfig._
  /**
   * Construct a namer.
   */
  @JsonIgnore
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    val MeshClientHeader = "l5d-ctx-mesh-client"
    val name = dst match {
      case None => throw new IllegalArgumentException("`dst` is a required field") with NoStackTrace
      case Some(dst) => Name.Path(dst)
    }
    val label = MeshInterpreterInitializer.configId
    val clientId = randomUUID().toString
    val Retry(baseRetry, maxRetry) = retry.getOrElse(defaultRetry)
    val backoffs = Backoff.exponentialJittered(baseRetry.seconds, maxRetry.seconds)
    val tlsParams = tls.map(_.params).getOrElse(Stack.Params.empty)
    val failureThresholdParams = failureThreshold.map(_.params).getOrElse(Stack.Params.empty)
    val headerIdentity = Map(MeshClientHeader -> clientId)

    val client = H2.client.withStack(H2HeaderInjector.module(headerIdentity) +: _)
      .withParams(H2.client.params ++ tlsParams ++ failureThresholdParams ++ params)
      .newService(name, label)

    root.getOrElse(DefaultRoot) match {
      case r@Path.Utf8(_) =>
        Client(clientId, r, client, backoffs, DefaultTimer)

      case r =>
        val msg = s"io.l5d.mesh: `root` may only contain a single path element (for now): ${r.show}"
        throw new IllegalArgumentException(msg) with NoStackTrace
    }
  }
}
