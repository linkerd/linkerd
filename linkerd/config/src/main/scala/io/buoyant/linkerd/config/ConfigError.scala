package io.buoyant.linkerd.config

import java.net.InetSocketAddress

trait ConfigError {
  def message: String
}

object NoRoutersSpecified extends ConfigError {
  def message = "At least one router must be specified in the configuration."
}

// TODO: this should serialize out the router configuration that's missing a label
object MissingLabel extends ConfigError {
  def message = "Router missing a label"
}
case class ConflictingLabels(name: String) extends ConfigError {
  def message = s"Multiple routers with the label $name"
}

case class ConflictingPorts(addr0: InetSocketAddress, addr1: InetSocketAddress) extends ConfigError {
  def message = s"Server conflict on port ${addr0.getPort}"
}
