package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonSubTypes}
import com.twitter.conversions.time._
import com.twitter.finagle.{param, Stack}
import com.twitter.finagle.buoyant.TotalTimeout
import com.twitter.finagle.service._
import com.twitter.finagle.buoyant.ParamsMaybeWith
import com.twitter.util.Duration
import io.buoyant.config.PolymorphicConfig
import io.buoyant.router.{ClassifiedRetries, RetryBudgetConfig}
import io.buoyant.router.RetryBudgetModule.{param => ev}

/**
 * SvcConfig is a trait containing protocol agnostic configuration options
 * that apply at the level of the logical name (i.e. the path stack).  This
 * trait can be mixed into a class to allow these options to be set on that
 * class as part of config deserialization.
 */
trait SvcConfig {

  var totalTimeoutMs: Option[Int] = None
  var retries: Option[RetriesConfig] = None

  @JsonIgnore
  def params(vars: Map[String, String]): Stack.Params = Stack.Params.empty
    .maybeWith(totalTimeoutMs.map(timeout => TotalTimeout.Param(timeout.millis)))
    .maybeWith(retries.flatMap(_.mkBackoff))
    .maybeWith(retries.flatMap(_.budget))
    .maybeWith(responseClassifier.map(param.ResponseClassifier(_)))

  /**
   * responseClassifier categorizes responses to determine whether
   * they are failures and if they are retryable.
   *
   * @note that unlike the other properties in this class, this `var`
   *       has a getter and setter. this is because the `H2SvcConfig`
   *       will use a distinct type for its response classifier, and
   *       we want to reuse the JSON property `"responseClassifier"`.
   *       Scala doesn't permit child classes to override mutable fields,
   *       but it does permit them to override `def`s, so `H2SvcConfig`
   *       *can* override the getter and setter for this field with
   *       `JsonIgnore` and reuse the `"responseClassifier"` JSON property.
   *
   */
  private[this] var _responseClassifierConfig: Option[ResponseClassifierConfig] =
    None

  @JsonProperty("responseClassifier")
  def responseClassifierConfig_=(r: Option[ResponseClassifierConfig]): Unit =
    _responseClassifierConfig = r

  @JsonProperty("responseClassifier")
  def responseClassifierConfig: Option[ResponseClassifierConfig] =
    _responseClassifierConfig

  @JsonIgnore
  def baseResponseClassifier: ResponseClassifier =
    ClassifiedRetries.Default

  @JsonIgnore
  def responseClassifier: Option[ResponseClassifier] =
    _responseClassifierConfig.map { classifier =>
      ClassifiedRetries.orElse(classifier.mk, baseResponseClassifier)
    }
}

case class RetriesConfig(
  backoff: Option[BackoffConfig] = None,
  budget: Option[RetryBudgetConfig] = None
) {

  @JsonIgnore
  def mkBackoff: Option[ClassifiedRetries.Backoffs] =
    backoff.map(_.mk).map(ClassifiedRetries.Backoffs(_))
}

@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[ConstantBackoffConfig], name = "constant"),
  new JsonSubTypes.Type(value = classOf[JitteredBackoffConfig], name = "jittered")
))
abstract class BackoffConfig extends PolymorphicConfig {
  @JsonIgnore
  def mk: Stream[Duration]
}

case class ConstantBackoffConfig(ms: Int) extends BackoffConfig {
  // ms defaults to 0 when not specified
  def mk = Backoff.constant(ms.millis)
}

/** See http://www.awsarchitectureblog.com/2015/03/backoff.html */
case class JitteredBackoffConfig(minMs: Option[Int], maxMs: Option[Int]) extends BackoffConfig {
  def mk = {
    val min = minMs match {
      case Some(ms) => ms.millis
      case None => throw new IllegalArgumentException("'minMs' must be specified")
    }
    val max = maxMs match {
      case Some(ms) => ms.millis
      case None => throw new IllegalArgumentException("'maxMs' must be specified")
    }
    Backoff.decorrelatedJittered(min, max)
  }
}

