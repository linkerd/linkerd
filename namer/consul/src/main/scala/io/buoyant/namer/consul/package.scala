package io.buoyant.namer

import com.twitter.finagle.Path
import com.twitter.logging.Logger
import com.twitter.util.{Activity, Updatable, Var}

package object consul {
  private[consul]type VarUp[T] = Var[T] with Updatable[T]
  private[consul]type ActUp[T] = VarUp[Activity.State[T]]
  val log = Logger.get("io.buoyant.namer.consul")

  /** path-extracted schema to make consul api calls */
  private[consul] case class PathScheme(
    dc: String,
    service: SvcKey,
    id: Path,
    subpath: Path
  )

}
