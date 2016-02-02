package io.buoyant.linkerd.config

import java.net.InetSocketAddress
import java.nio.file.InvalidPathException

import cats.data.NonEmptyList
import com.fasterxml.jackson.core.JsonParseException

trait ConfigError {
  def message: String
}

object ConfigError {
  /*
   * Attempts to transform a parsing exception into one or more ConfigErrors. Unrecognized
   * exceptions will be re-thrown.
   */
  def transform(t: Throwable): NonEmptyList[ConfigError] = t match {
    case jpe: JsonParseException => NonEmptyList(InvalidSyntax(jpe.getMessage))
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

object MissingPath extends ConfigError {
  def message = "All namers require a `prefix` path."
}

case class InvalidPath(path: String, ex: IllegalArgumentException) extends ConfigError {
  def message = s"Invalid prefix $path specified, problem was ${ex.getMessage}"
}

object MissingRootDir extends ConfigError {
  def message = "io.l5d.fs namer requires a `rootDir` specified"
}

case class InvalidRootDir(path: String, ex: InvalidPathException) extends ConfigError {
  def message = s"io.l5d.fs 'rootDir' is not a valid filesystem path: ${ex.getMessage}"
}

case class RootDirNotDirectory(path: java.nio.file.Path) extends ConfigError {
  def message = s"io.l5d.fs 'rootDir' is not a directory: $path"
}
