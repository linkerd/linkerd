package io.buoyant

import com.twitter.finagle.{Service, http => fhttp}
import com.twitter.logging.Logger

package object k8s {
  type Client = Service[fhttp.Request, fhttp.Response]

  private[k8s] val log = Logger.get("k8s")
}
