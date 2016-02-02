package io.buoyant.linkerd.config

import cats.data.Validated._
import cats.data.ValidatedNel
import cats.implicits._

trait LinkerConfig {
  def baseDtab: Option[String]
  def routers: Option[Seq[RouterConfig]]
  def namers: Option[Seq[NamerConfig]]
  def failFast: Option[Boolean]

  // Currently, the only thing we require of a Linker is that it has at least one Router configured.
  def validated: ValidatedNel[ConfigError, LinkerConfig.Validated] = {
    def validatedRouters = routers
      .filter { _.nonEmpty }
      .map(RouterConfig.validateRouters(this))
      .getOrElse(invalidNel[ConfigError, Seq[RouterConfig.Validated]](NoRoutersSpecified))
    def validatedNamers: ValidatedNel[ConfigError, Seq[NamerConfig.Validated]] =
      namers.getOrElse(Nil).map { _.withDefaults(this).validated }.toList.sequenceU

    (validatedRouters |@| validatedNamers).map {
      new LinkerConfig.Validated(this, _, _)
    }
  }
}

object LinkerConfig {
  case class Impl(
    baseDtab: Option[String],
    failFast: Option[Boolean],
    namers: Option[Seq[NamerConfig]],
    routers: Option[Seq[RouterConfig]]
  ) extends LinkerConfig

  // Represents a fully validated LinkerConfig, with defaults applied, suitable for configuring a Linker.
  // NOTE: there are currently no defaults for Linkers, but probably will be in the future.
  class Validated(base: LinkerConfig, val routers: Seq[RouterConfig.Validated], val namers: Seq[NamerConfig.Validated]) {
    def baseDtab: Option[String] = base.baseDtab
  }
}
