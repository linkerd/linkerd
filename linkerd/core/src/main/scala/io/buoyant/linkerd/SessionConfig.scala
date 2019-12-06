package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.service.ExpiringService

trait SessionConfig {

  def lifeTimeMs: Option[Int]
  def idleTimeMs: Option[Int]

  @JsonIgnore
  private[this] val default = ExpiringService.Param.param.default

  @JsonIgnore
  def param = ExpiringService.Param(
    lifeTime = lifeTimeMs.map(_.millis).getOrElse(default.lifeTime),
    idleTime = idleTimeMs.map(_.millis).getOrElse(default.idleTime)
  )

}
