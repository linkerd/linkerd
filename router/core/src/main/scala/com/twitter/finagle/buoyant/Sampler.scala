package com.twitter.finagle.buoyant

import com.twitter.finagle.tracing.{Record, TraceId, Tracer}
import com.twitter.util.Var
import scala.util.Random

/**
 * Decides if a trace should be sampled.
 *
 * Based on com.twitter.finagle.zipkin.thrift.Sampler.
 */
case class Sampler(sampleRate: Var[Float]) {

  /**
   * Returns true if the given trace id should be sampled.
   */
  def apply(id: Long): Boolean = {
    val r = sampleRate.sample().min(1f).max(0f) // fit on [0.0, 1.0]
    val v = math.abs(id ^ Sampler.salt) % 10000
    val t = r * 10000
    v < t
  }
}

object Sampler {

  /**
   * This salt is used to prevent traceId collisions between machines.
   * By giving each system a random salt, it is less likely that two
   * processes will sample the same subset of trace ids.
   */
  private val salt = new Random().nextLong()

  /** A sampler with a fixed sample rate. */
  def apply(v: Float): Sampler = {
    require(v >= 0f && v <= 1f)
    Sampler(Var.value(v))
  }
}
