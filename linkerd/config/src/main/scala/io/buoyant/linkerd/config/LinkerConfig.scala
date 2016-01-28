package io.buoyant.linkerd.config

trait LinkerConfig {
  def baseDtab: Option[String]
  def routers: Option[Seq[RouterConfig]]

  def withDefaults = new Defaults(this)
  class Defaults(base: LinkerConfig) extends LinkerConfig {
    def baseDtab: Option[String] = base.baseDtab
    def routers: Option[Seq[RouterConfig]] = base.routers map { _ map { _.withDefaults(this) } }
  }

  // Returns Nil if there are no errors detected.
  // TODO: determine if we should instead use a Validation library,
  // like the one provided by cats.
  def validate: Seq[ConfigError] = {
    if (routers.isEmpty) {
      Seq(NoRoutersSpecified)
    } else {
      val (errors, _) = routers.getOrElse(Nil).foldLeft((Seq.empty[ConfigError], Seq.empty[RouterConfig])) {
        case ((prevErrors, prevRouters), router) =>
          (router.validate(prevRouters), prevRouters :+ router)
      }
      errors
    }
  }
}

object LinkerConfig {
  case class Impl(
    baseDtab: Option[String], // This could instead be a Dtab if we add a custom deserializer
    routers: Option[Seq[RouterConfig]]
  ) extends LinkerConfig
}


