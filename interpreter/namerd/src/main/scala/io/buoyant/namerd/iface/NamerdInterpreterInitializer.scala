package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.liveness.FailureDetector
import com.twitter.finagle.liveness.FailureDetector.ThresholdConfig
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param.Label
import com.twitter.finagle.service._
import com.twitter.logging.Logger
import com.twitter.util.{NonFatal => _, _}
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
      disableValidation = Some(false),
      commonName = Some(commonName),
      trustCerts = caCert.map(Seq(_)),
      clientAuth = None
    ).params
  }
}

case class FailureThresholdConfig(
  minPeriodMs: Option[Int],
  @JsonDeserialize(contentAs = classOf[java.lang.Double]) threshold: Option[Double],
  windowSize: Option[Int],
  closeTimeoutMs: Option[Int]
) {
  @JsonIgnore
  def params: Stack.Params = {
    val thresholdConfig = ThresholdConfig(
      minPeriodMs.map(_.milliseconds).getOrElse(FailureThresholdConfig.DefaultMinPeriod),
      threshold.getOrElse(FailureThresholdConfig.DefaultThreshold),
      windowSize.getOrElse(FailureThresholdConfig.DefaultWindowSize),
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
      case e: Failure if e.isFlagged(Failure.Interrupted) => true
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

    val iface = client.newIface[thrift.Namer.FutureIface](name, label)

    val ns = namespace.getOrElse("default")
    val Label(routerLabel) = params[Label]

    new ThriftNamerClient(iface, ns, backoffs, stats) with Admin.WithHandlers with Admin.WithNavItems {
      val handler = new NamerdHandler(Seq(routerLabel -> config), Map(routerLabel -> this))

      override def adminHandlers: Seq[Handler] =
        Seq(Handler("/namerd", handler, css = Seq("delegator.css")))

      override def navItems: Seq[NavItem] = Seq(NavItem("namerd", "namerd"))
    }
  }
}

object NamerdInterpreterConfig {
  def kind = classOf[NamerdInterpreterConfig].getCanonicalName
}

object FailureThresholdConfig {

  val DefaultMinPeriod = 5.seconds
  val DefaultThreshold = 2.0
  val DefaultWindowSize = 100
  val DefaultCloseTimeout = 4.seconds

  def defaultStackParam = StackParams.empty + FailureDetector.Param(ThresholdConfig(
    DefaultMinPeriod,
    DefaultThreshold,
    DefaultWindowSize,
    DefaultCloseTimeout
  ))
}
