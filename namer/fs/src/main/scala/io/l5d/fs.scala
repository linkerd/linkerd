package io.l5d

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Stack, Path}
import io.buoyant.config.types.Directory
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.namer.fs.WatchingNamer

class FsInitializer extends NamerInitializer {
  val configClass = classOf[fs]
}

object FsInitializer extends FsInitializer

case class fs(rootDir: Directory) extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.fs")

  /**
   * Construct a namer.
   */
  @JsonIgnore
  def newNamer(params: Stack.Params) = new WatchingNamer(rootDir.path, prefix)
}
