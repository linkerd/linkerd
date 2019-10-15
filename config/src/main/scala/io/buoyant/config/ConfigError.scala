package io.buoyant.config

import com.fasterxml.jackson.databind.jsontype.NamedType
import java.net.InetSocketAddress
import scala.util.control.NoStackTrace

trait ConfigError extends NoStackTrace

object NoRoutersSpecified extends ConfigError {
  def message = "At least one router must be specified in the configuration."
}

case class ConflictingSubtypes(t0: NamedType, t1: NamedType) extends ConfigError {
  def message = s"Conflicting subtypes: $t0, $t1"
}

case class ConflictingLabels(name: String) extends ConfigError {
  def message = s"Multiple routers with the label $name"
}
case class ConflictingStreamingOptions(name: String) extends ConfigError {
  def message = s"Conflicting streaming options set. Can't disable streaming and set streamAfterContentLengthKB in $name"
}

case class ConflictingPorts(
  addr0: InetSocketAddress,
  addr1: InetSocketAddress
) extends ConfigError {
  def message = s"Server conflict on port ${addr0.getPort}"
}
