package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes, JsonTypeInfo}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{ServiceFactory, Stack, Stackable}
import io.buoyant.router.RouterLabel
import io.buoyant.router.http.AddForwardedHeader

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
  def mk(params: Stack.Params): AddForwardedHeader.Labeler
}

/** Labels an endpoint by its IP address, if it's known. */
class IpLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) = AddForwardedHeader.Labeler.ClearIp
}

/** Labels an endpoint by its IP and port like `ip:port` */
class IpPortLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) = AddForwardedHeader.Labeler.ClearIpPort
}

/** Generates a random obfuscated label for each request. */
class RequestRandomLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) =
    AddForwardedHeader.Labeler.ObfuscatedRandom.PerRequest()
}

/** Generates a random obfuscated label for each connection. */
class ConnectionRandomLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) =
    AddForwardedHeader.Labeler.ObfuscatedRandom.PerConnection()
}

/** Uses the router name as an obfuscated label. */
class RouterLabelerConfig extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) =
    AddForwardedHeader.Labeler.ObfuscatedStatic(params[RouterLabel.Param].label)
}

/** Uses the given string as an obfuscated label. */
case class StaticLabelerConfig(label: String) extends LabelerConfig {

  @JsonIgnore
  override def mk(params: Stack.Params) =
    AddForwardedHeader.Labeler.ObfuscatedStatic(label)
}

case class AddForwardedHeaderConfig(
  by: Option[LabelerConfig],
  `for`: Option[LabelerConfig]
) {

  /** Appends AddForwardedHeader params to the given params. */
  @JsonIgnore
  def ++:(params: Stack.Params): Stack.Params =
    params + AddForwardedHeader.Enabled(true) + byParam(params) + forParam(params)

  @JsonIgnore
  private[this] def byParam(params: Stack.Params): AddForwardedHeader.Labeler.By =
    by match {
      case None => AddForwardedHeader.Labeler.By.default
      case Some(config) => AddForwardedHeader.Labeler.By(config.mk(params))
    }

  @JsonIgnore
  private[this] def forParam(params: Stack.Params): AddForwardedHeader.Labeler.For =
    `for` match {
      case None => AddForwardedHeader.Labeler.For.default
      case Some(config) => AddForwardedHeader.Labeler.For(config.mk(params))
    }
}

/**
 * Translates AddForwardedHeaderConfig.Param into AddForwardedHeader
 * configuration parameters.
 */
object AddForwardedHeaderConfig {

  case class Param(config: Option[AddForwardedHeaderConfig])
  implicit object Param extends Stack.Param[Param] {
    val default = Param(None)
  }

  /**
   * Configures parameters for `AddForwardedHeader`.
   *
   * Because `AddForwardedHeaderConfig` types may depend on stack
   * parameters (for instance, `Server.RouterLabel`)
   */
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module[ServiceFactory[Request, Response]] {
      val role = Stack.Role("ConfigureAddForwardedHeader")
      val description = AddForwardedHeader.module.description
      val parameters = Seq(implicitly[Stack.Param[Param]])

      private type Stk = Stack[ServiceFactory[Request, Response]]

      def make(params: Stack.Params, stk: Stk) = params[Param] match {
        case Param(None) => stk
        case Param(Some(config)) =>
          // Wrap the underlying stack, applying the ForwardedHeaderConfig
          val mkNext: (Stack.Params, Stk) => Stk =
            (prms, next) => Stack.Leaf(this, next.make(prms ++: config))
          Stack.Node(this, mkNext, stk)
      }
    }

}
