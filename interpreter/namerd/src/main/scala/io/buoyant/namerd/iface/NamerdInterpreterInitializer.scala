package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.liveness.FailureDetector
import com.twitter.finagle.liveness.FailureDetector.ThresholdConfig
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param.Label
import com.twitter.finagle.service._
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.admin.Admin.{Handler, NavItem}
import io.buoyant.namer.{InterpreterInitializer, NamespacedInterpreterConfig}
import io.buoyant.namerd.iface.{thriftscala => thrift}

/**
 * The namerd interpreter offloads the responsibilities of name resolution to
 * the namerd service via the namerd thrift API.  Any namers configured in this
 * linkerd are not used.
 */
class NamerdInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[NamerdInterpreterConfig]
  override def configId: String = "io.l5d.namerd"
}

object NamerdInterpreterInitializer extends NamerdInterpreterInitializer

case class Retry(
  baseSeconds: Int,
  maxSeconds: Int
) {
  if (baseSeconds <= 0 || maxSeconds <= 0 || baseSeconds > maxSeconds) {
    val msg = s"illegal retry values: baseSeconds=$baseSeconds maxSeconds=$maxSeconds"
    throw new IllegalArgumentException(msg)
  }
}

case class ClientTlsConfig(commonName: String, caCert: Option[String]) {
  def params: Stack.Params = {
    TlsClientConfig(
      enabled = Some(true),
      disableValidation = Some(false),
      commonName = Some(commonName),
      trustCerts = caCert.map(Seq(_)),
      clientAuth = None
    ).params
  }
}

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

case class NamerdInterpreterConfig(
  dst: Option[Path],
  namespace: Option[String],
  retry: Option[Retry],
  tls: Option[ClientTlsConfig],
  failureThreshold: Option[FailureThresholdConfig]
) extends NamespacedInterpreterConfig { config =>

  @JsonIgnore
  private[this] val log = Logger.get()

  @JsonIgnore
  val defaultRetry = Retry(5, 10.minutes.inSeconds)

  /**
   * Construct a namer.
   */
  @JsonIgnore
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    val name = dst match {
      case None => throw new IllegalArgumentException("`dst` is a required field")
      case Some(dst) => Name.Path(dst)
    }
    val label = s"interpreter/${NamerdInterpreterConfig.kind}"

    val Retry(baseRetry, maxRetry) = retry.getOrElse(defaultRetry)
    val backoffs = Backoff.exponentialJittered(baseRetry.seconds, maxRetry.seconds)

    val monitor = Monitor.mk {
      case e: Failure if e.isFlagged(FailureFlags.Interrupted) => true
    }

    val param.Stats(stats0) = params[param.Stats]
    val stats = stats0.scope(label)

    val tlsParams = tls.map(_.params).getOrElse(Stack.Params.empty)
    val failureThresholdParams = failureThreshold.map(_.params).getOrElse(FailureThresholdConfig.defaultStackParam)

    val client = ThriftMux.client
      .withParams(ThriftMux.client.params ++ tlsParams ++ failureThresholdParams ++ params)
      .withRetryBudget(RetryBudget.Empty) // we will only do retries as part of the Var.async loop
      .withMonitor(monitor)
      .withSessionQualifier.noFailFast
      .withSessionQualifier.noFailureAccrual

    val iface = client.build[thrift.Namer.MethodPerEndpoint](name, label)

    val ns = namespace.getOrElse("default")
    val Label(routerLabel) = params[Label]

    new ThriftNamerClient(iface, ns, backoffs, stats)
  }
}

object NamerdInterpreterConfig {
  def kind = classOf[NamerdInterpreterConfig].getCanonicalName
}

object FailureThresholdConfig {

  val DefaultMinPeriod = 5.seconds
  val DefaultCloseTimeout = 4.seconds

  def defaultStackParam = StackParams.empty + FailureDetector.Param(ThresholdConfig(
    DefaultMinPeriod,
    DefaultCloseTimeout
  ))
}
