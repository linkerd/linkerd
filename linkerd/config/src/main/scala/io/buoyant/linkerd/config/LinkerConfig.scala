package io.buoyant.linkerd.config

import com.twitter.finagle.Dtab

case class LinkerConfig(
  baseDtab: Option[String], // This could instead be a Dtab if we add a custom deserializer
  routers: Seq[RouterConfig]
)
