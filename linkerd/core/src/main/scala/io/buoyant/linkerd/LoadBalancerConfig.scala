package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes, JsonTypeInfo}
import com.twitter.conversions.time._
import com.twitter.finagle.Stack
import com.twitter.finagle.loadbalancer.LoadBalancerFactory.EnableProbation
import com.twitter.finagle.loadbalancer.{Balancers, LoadBalancerFactory}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(Array(
  new Type(value = classOf[P2C], name = "p2c"),
  new Type(value = classOf[P2CEwma], name = "ewma"),
  new Type(value = classOf[Aperture], name = "aperture"),
  new Type(value = classOf[Heap], name = "heap")
))
trait LoadBalancerConfig {
  val factory: LoadBalancerFactory

  val enableProbation: Option[Boolean] = None

  @JsonIgnore
  def clientParams = Stack.Params.empty + LoadBalancerFactory.Param(factory) +
    LoadBalancerFactory.EnableProbation(enableProbation.getOrElse(true))
}

case class P2C(maxEffort: Option[Int]) extends LoadBalancerConfig {
  @JsonIgnore
  val factory = Balancers.p2c(maxEffort.getOrElse(Balancers.MaxEffort))
}

case class P2CEwma(decayTimeMs: Option[Int], maxEffort: Option[Int]) extends LoadBalancerConfig {
  @JsonIgnore
  val factory = Balancers.p2cPeakEwma(
    decayTime = decayTimeMs.map(_.millis).getOrElse(10.seconds),
    maxEffort = maxEffort.getOrElse(Balancers.MaxEffort)
  )
}

case class Aperture(
  smoothWindowMs: Option[Int],
  maxEffort: Option[Int],
  lowLoad: Option[Double],
  highLoad: Option[Double],
  minAperture: Option[Int]
) extends LoadBalancerConfig {
  @JsonIgnore
  val factory = Balancers.aperture(
    smoothWin = smoothWindowMs.map(_.millis).getOrElse(5.seconds),
    maxEffort = maxEffort.getOrElse(Balancers.MaxEffort),
    lowLoad = lowLoad.getOrElse(0.5),
    highLoad = highLoad.getOrElse(2.0),
    minAperture = minAperture.getOrElse(1)
  )
}

class Heap extends LoadBalancerConfig {
  @JsonIgnore
  val factory = Balancers.heap()
}
