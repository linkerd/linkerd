package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes, JsonTypeInfo}
import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.PathMatcher
import io.buoyant.config.PolymorphicConfig
import io.buoyant.router.StackRouter.Client.{PathParams, PerPathParams}

/**
 * Svc is the polymorphic type the jackson will use to deserialize the
 * `service` section of a linkerd config.
 */
@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY,
  property = "kind",
  visible = true,
  defaultImpl = classOf[DefaultSvcImpl]
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[DefaultSvcImpl], name = "io.l5d.global"),
  new JsonSubTypes.Type(value = classOf[StaticSvcImpl], name = "io.l5d.static")
))
abstract class Svc extends PolymorphicConfig {
  @JsonIgnore
  def pathParams: PerPathParams
}

/**
 * DefaultSvc mixes in SvcConfig so that path-stack configuration properties
 * can be specified directly on the `service` object in the linkerd config.
 * This is a trait so that it can be mixed in to protocol specific versions.
 */
trait DefaultSvc extends SvcConfig { self: Svc =>
  kind = "io.l5d.global"

  @JsonIgnore
  private[this] val matchAll = PathMatcher("/")

  @JsonIgnore
  private[this] val mk: Map[String, String] => Stack.Params = { vars =>
    params(vars)
  }

  @JsonIgnore
  def pathParams = PerPathParams(Seq(PathParams(matchAll, mk)))
}

class DefaultSvcImpl extends Svc with DefaultSvc

/**
 * StaticSvc consists of a list of PrefixConfigs.  This is a trait so that it
 * can be mixed in to protocol specific versions.
 */
trait StaticSvc { self: Svc =>
  val configs: Seq[SvcPrefixConfig]

  @JsonIgnore
  def pathParams = PerPathParams(configs.map { config =>
    PathParams(config.prefix, config.params)
  })
}

class StaticSvcImpl(val configs: Seq[SvcPrefixConfig]) extends Svc with StaticSvc

class SvcPrefixConfig(val prefix: PathMatcher) extends SvcConfig