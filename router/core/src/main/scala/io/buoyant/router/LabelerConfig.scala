package io.buoyant.router

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes, JsonTypeInfo}
import com.twitter.finagle.Stack

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = "kind"
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[IpLabelerConfig], name = "ip"),
  new JsonSubTypes.Type(value = classOf[IpPortLabelerConfig], name = "ip:port"),
  new JsonSubTypes.Type(value = classOf[ConnectionRandomLabelerConfig], name = "connectionRandom"),
  new JsonSubTypes.Type(value = classOf[RequestRandomLabelerConfig], name = "requestRandom"),
  new JsonSubTypes.Type(value = classOf[RouterLabelerConfig], name = "router"),
  new JsonSubTypes.Type(value = classOf[StaticLabelerConfig], name = "static")
))
trait LabelerConfig {
  @JsonIgnore
  def mk(params: Stack.Params): ForwardedHeaderLabeler.Labeler
}

/** Labels an endpoint by its IP address, if it's known. */
class IpLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) = ForwardedHeaderLabeler.ClearIp
}

/** Labels an endpoint by its IP and port like `ip:port` */
class IpPortLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) = ForwardedHeaderLabeler.ClearIpPort
}

/** Generates a random obfuscated label for each request. */
class RequestRandomLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) =
    ForwardedHeaderLabeler.ObfuscatedRandom.PerRequest()
}

/** Generates a random obfuscated label for each connection. */
class ConnectionRandomLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) =
    ForwardedHeaderLabeler.ObfuscatedRandom.PerConnection()
}

/** Uses the router name as an obfuscated label. */
class RouterLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) =
    ForwardedHeaderLabeler.ObfuscatedStatic(params[RouterLabel.Param].label)
}

/** Uses the given string as an obfuscated label. */
case class StaticLabelerConfig(label: String) extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) =
    ForwardedHeaderLabeler.ObfuscatedStatic(label)
}
