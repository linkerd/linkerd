package io.buoyant.namer

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes}
import com.twitter.finagle.Backoff
import com.twitter.conversions.DurationOps._
import io.buoyant.config.PolymorphicConfig

@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[ConstantBackoffConfig], name = "constant"),
  new JsonSubTypes.Type(value = classOf[JitteredBackoffConfig], name = "jittered")
))
abstract class BackoffConfig extends PolymorphicConfig {
  @JsonIgnore
  def mk: Backoff
}

case class ConstantBackoffConfig(ms: Int) extends BackoffConfig {
  // ms defaults to 0 when not specified
  def mk = Backoff.constant(ms.millis)
}

/** See https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/ */
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
