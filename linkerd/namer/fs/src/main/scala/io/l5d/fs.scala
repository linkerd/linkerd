package io.l5d

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Stack, Path}
import io.buoyant.linkerd.config.types.Directory
import io.buoyant.linkerd.namer.fs.WatchingNamer
import io.buoyant.linkerd.{NamerConfig, NamerInitializer}

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
