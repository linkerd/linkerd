package io.buoyant.linkerd.config

case class ServerConfig(
  ip: Option[String],
  port: Option[Int]
)
