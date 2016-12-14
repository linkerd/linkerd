package io.buoyant

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Service, http => fhttp}
import com.twitter.logging.Logger
import io.buoyant.config.JsonStreamParser

/**
 * This package contains representations of objects returned by multiple versions of the Kubernetes
 * API. Version-specific objects should go in sub-packages (see v1.scala).
 */
package object k8s {
  type Client = Service[fhttp.Request, fhttp.Response]

  private[k8s] val log = Logger.get("k8s")

  val Json = {
    val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    LoadService[SerializationModule].foreach { svc => mapper.registerModule(svc.module) }
    new JsonStreamParser(mapper)
  }
}
