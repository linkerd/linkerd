package io.buoyant.namerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.service.Backoff
import com.twitter.conversions.time._
import com.twitter.util.Duration

abstract class SvcConfig {
  @JsonIgnore
  def mk: Stream[Duration]
}

/** See http://www.awsarchitectureblog.com/2015/03/backoff.html */
case class BackoffConfig(minMs: Option[Int], maxMs: Option[Int]) extends SvcConfig {
  def mk = {
    val min = minMs match {
      case Some(ms) => ms.millis
      case None => throw new IllegalArgumentException("'minMs' must be specified")
    }
    val max = maxMs match {
      case Some(ms) => ms.millis
      case None => throw new IllegalArgumentException("'maxMs' must be specified")
    }
    Backoff.exponentialJittered(min, max)
  }
}
