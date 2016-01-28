package io.buoyant.linkerd.config

import cats.data.Validated._
import cats.data.ValidatedNel

trait LinkerConfig {
  def baseDtab: Option[String]
  def routers: Option[Seq[RouterConfig]]
  def failFast: Option[Boolean]

  // Currently, the only thing we require of a Linker is that it has at least one Router configured,
  // TODO: determine what else we might want to validate. Namers?
  def validated: ValidatedNel[ConfigError, LinkerConfig.Validated] = {
    routers
      .filter { _.nonEmpty }
      .map(RouterConfig.validateRouters(this))
      .getOrElse(invalidNel[ConfigError, Seq[RouterConfig.Validated]](NoRoutersSpecified))
      .map { rs => new LinkerConfig.Validated(this, rs) }
  }
}

object LinkerConfig {
  case class Impl(
    baseDtab: Option[String],
    failFast: Option[Boolean],
    routers: Option[Seq[RouterConfig]]
  ) extends LinkerConfig

  // Represents a fully validated LinkerConfig, with defaults applied, suitable for configuring a Linker.
  // NOTE: there are currently no defaults for Linkers, but probably will be in the future.
  class Validated(base: LinkerConfig, val routers: Seq[RouterConfig.Validated]) {
    def baseDtab: Option[String] = base.baseDtab
  }
}
