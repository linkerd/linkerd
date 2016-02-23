package io.buoyant.linkerd

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType

trait ConfigInitializer {

  def configClass: Class[_]
  def configId: String = configClass.getName

  lazy val namedType = new NamedType(configClass, configId)

  def registerSubtypes(mapper: ObjectMapper): Unit = {
    mapper.registerSubtypes(namedType)
  }
}
