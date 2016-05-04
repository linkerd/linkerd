package io.buoyant.linkerd

import com.twitter.finagle.{Announcer => FAnnouncer, Path}

abstract class Announcer extends FAnnouncer {
  def concreteName(name: String): Path
}




