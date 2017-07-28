package com.medallia.servicediscovery

import org.codehaus.jackson.annotate.{JsonCreator, JsonProperty}

import scala.beans.BeanProperty

/** ServiceDiscovery payload, just a generic description for now */
case class ServiceInstanceInfo @JsonCreator() (
  @JsonProperty("description")@BeanProperty description: String
) {

}
