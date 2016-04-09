package io.buoyant

import com.twitter.finagle.{Service, http => fhttp}
import com.twitter.logging.Logger

/**
 * This package contains representations of objects returned by multiple versions of the Kubernetes
 * API. Version-specific objects should go in sub-packages (see v1.scala).
 */
package object k8s {
  type Client = Service[fhttp.Request, fhttp.Response]

  private[k8s] val log = Logger.get("k8s")
}
