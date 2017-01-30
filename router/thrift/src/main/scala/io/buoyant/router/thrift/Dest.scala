package io.buoyant.router.thrift

import com.twitter.finagle.Path
import com.twitter.util.Local

/** A thread local context for storing the destination of the current request. */
object Dest {
  private val l = new Local[Path]

  def local: Path = l().getOrElse(Path.empty)

  def local_=(path: Path): Unit = l() = path
}
