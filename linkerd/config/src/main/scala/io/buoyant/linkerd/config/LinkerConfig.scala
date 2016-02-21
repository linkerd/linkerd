package io.buoyant.linkerd.config

import cats.Apply

trait LinkerConfig extends CommonRouterConfig {
  def routers: Option[Seq[RouterConfig]]
  def namers: Option[Seq[NamerConfig]]

  // Currently, the only thing we require of a Linker is that it has at least one Router configured.
  def validated: ValidatedConfig[LinkerConfig.Validated] = {
    import cats.std.list._
    import cats.syntax.traverse._
    def validatedRouters: ValidatedConfig[Seq[RouterConfig.Validated]] = routers
      .filter { _.nonEmpty }
      .map(RouterConfig.validateRouters(this))
      .getOrElse(invalid(NoRoutersSpecified))

    def validatedNamers: ValidatedConfig[Seq[NamerConfig.Validated]] =
      namers.getOrElse(Nil).map { _.withDefaults(this).validated }.toList.sequenceU

    Apply[ValidatedConfig].map2(validatedRouters, validatedNamers) {
      new LinkerConfig.Validated(this, _, _)
    }

  }
}

object LinkerConfig {
  case class Impl(
    namers: Option[Seq[NamerConfig]],
    routers: Option[Seq[RouterConfig]]
  ) extends LinkerConfig

  // Represents a fully validated LinkerConfig, with defaults applied, suitable for configuring a Linker.
  // NOTE: there are currently no defaults for Linkers, but probably will be in the future.
  class Validated(base: LinkerConfig, val routers: Seq[RouterConfig.Validated], val namers: Seq[NamerConfig.Validated]) {
    def baseDtab: Option[String] = base.baseDtab
  }
}
