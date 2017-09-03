package io.buoyant.consul.v1

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{DeserializationContext, SerializerProvider}
import io.buoyant.config.{ConfigDeserializer, ConfigSerializer}

object HealthStatus extends Enumeration {
  type HealthStatus = Value
  val Passing, Warning, Critical, Maintenance = Value
  def withNameSafe(name: String): Value =
    values.find(_.toString.toLowerCase == name.toLowerCase).getOrElse(Passing)
  /*
   * worstCase returns the "worst" status of two health checks. Because a given
   * entry may have many service and node-level health checks attached, this
   * function can be used to determine the most representative status as a
   * single enum value using the following heuristic:
   *
   *  maintenance > critical > warning > passing
  */
  def worstCase(s1: Value, s2: Value): Value = if (s1.id > s2.id) s1 else s2
}

class HealthStatusDeserializer extends ConfigDeserializer[HealthStatus.Value] {

  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): HealthStatus.Value =
    catchMappingException(ctxt) {
      _parseString(jp, ctxt) match {
        case "passing" => HealthStatus.Passing
        case "warning" => HealthStatus.Warning
        case "critical" => HealthStatus.Critical
        case "maintenance" => HealthStatus.Maintenance
        case unknown =>
          throw new IllegalArgumentException(s"Illegal Consul health status: $unknown")
      }
    }
}

class HealthStatusSerializer extends ConfigSerializer[HealthStatus.Value] {
  override def serialize(
    mode: HealthStatus.Value,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(mode.toString.toLowerCase)
}
