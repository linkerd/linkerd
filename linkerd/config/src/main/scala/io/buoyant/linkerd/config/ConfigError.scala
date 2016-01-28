package io.buoyant.linkerd.config

import java.net.InetSocketAddress

import com.fasterxml.jackson.core.JsonParseException

trait ConfigError {
  def message: String
}

object ConfigError {
  /*
   * Attempts to transform a parsing exception into a ConfigError. Unrecognized
   * exceptions will be re-thrown.
   */
  def transform(t: Throwable): ConfigError = t match {
    case jpe: JsonParseException => InvalidSyntax(jpe.getMessage)
    case _ => throw (t)
  }
}

object NoRoutersSpecified extends ConfigError {
  def message = "At least one router must be specified in the configuration."
}

case class InvalidSyntax(msg: String) extends ConfigError {
  def message = s"Invalid JSON or YAML syntax in configuration file: $msg"
}

// TODO: this should serialize out the router configuration that's missing a label
object MissingLabel extends ConfigError {
  def message = "Router missing a label"
}

object MissingDtab extends ConfigError {
  def message = "Router missing a DTab"
}

case class InvalidDtab(bad: String, ex: IllegalArgumentException) extends ConfigError {
  def message = s"dtab $bad failed to parse due to ${ex.getMessage}"
}
case class ConflictingLabels(name: String) extends ConfigError {
  def message = s"Multiple routers with the label $name"
}

case class ConflictingPorts(addr0: InetSocketAddress, addr1: InetSocketAddress) extends ConfigError {
  def message = s"Server conflict on port ${addr0.getPort}"
}

case class InvalidPort(port: Int) extends ConfigError {
  def message = s"Invalid port specified: $port"
}
