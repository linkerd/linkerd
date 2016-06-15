package io.buoyant.linkerd

import com.twitter.conversions.time._
import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.util.Future
import io.buoyant.config.ConfigInitializer
import scala.collection.mutable

trait MetricsExporterInitializer extends ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait MetricsExporterConfig {

  @JsonProperty("periodSecs")
  var _periodSecs: Option[Int] = None

  @JsonIgnore
  def period = _periodSecs.map(_.seconds).getOrElse(1.minute)

  @JsonIgnore
  def export(metrics: mutable.Map[String, Number]): Future[Unit]
}
