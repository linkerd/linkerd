package io.buoyant.namer.fs

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import io.buoyant.config.types.Directory
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class FsInitializer extends NamerInitializer {
  val configClass = classOf[FsConfig]
  override def configId = "io.l5d.fs"
}

object FsInitializer extends FsInitializer

case class FsConfig(rootDir: Directory) extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.fs")

  /**
   * Construct a namer.
   */
  @JsonIgnore
  def newNamer(params: Stack.Params) = new WatchingNamer(rootDir.path, prefix)
}
