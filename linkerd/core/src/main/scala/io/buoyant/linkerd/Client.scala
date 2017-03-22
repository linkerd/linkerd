package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes, JsonTypeInfo}
import com.twitter.finagle.{Dentry, Path, Stack}
import com.twitter.finagle.buoyant.PathMatcher
import io.buoyant.config.PolymorphicConfig
import io.buoyant.router.StackRouter.Client.{ClientParams, PerClientParams}

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY,
  property = "kind",
  visible = true,
  defaultImpl = classOf[DefaultClientImpl]
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[DefaultClientImpl], name = "io.l5d.default"),
  new JsonSubTypes.Type(value = classOf[StaticClientImpl], name = "io.l5d.static")
))
abstract class Client extends PolymorphicConfig {
  @JsonIgnore
  def clientParams: PerClientParams
}

trait DefaultClient extends Client with ClientConfig {
  kind = "io.l5d.default"

  @JsonIgnore
  private[this] val matchAll = PathMatcher("/")

  @JsonIgnore
  private[this] val mk: Map[String, String] => Stack.Params = { vars =>
    params(vars)
  }

  @JsonIgnore
  def clientParams = PerClientParams(Seq(ClientParams(matchAll, mk)))
}

class DefaultClientImpl extends Client with DefaultClient

trait StaticClient {
  val configs: Seq[PrefixConfig]

  @JsonIgnore
  def clientParams = PerClientParams(configs.map { config =>
    ClientParams(config.prefix, config.params)
  })
}

class StaticClientImpl(val configs: Seq[PrefixConfig]) extends Client with StaticClient

class PrefixConfig(val prefix: PathMatcher) extends ClientConfig