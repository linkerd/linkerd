package com.twitter.finagle
package buoyant.linkerd

import com.twitter.finagle.client.StackClient
import com.twitter.finagle.http.{DelayedReleaseService, Request, Response}

object DelayedRelease {

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = StackClient.Role.prepFactory
      val description = "Prevents an HTTP service from being closed until its response completes"
      def make(next: ServiceFactory[Request, Response]) =
        next.map(new DelayedReleaseService(_))
    }
}
