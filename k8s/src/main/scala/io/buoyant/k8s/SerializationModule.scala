package io.buoyant.k8s

import com.fasterxml.jackson.databind.module.SimpleModule

/**
 * Class used to provide LoadService-compatible serialization/deserialization plugins to k8s JSON parsing.
 */
abstract class SerializationModule {
  def module: SimpleModule
}
