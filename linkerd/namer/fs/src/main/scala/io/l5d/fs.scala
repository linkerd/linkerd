package io.l5d

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Stack, Path}
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.config.types.Directory
import io.buoyant.linkerd.namer.fs.WatchingNamer
import io.buoyant.linkerd.{NamerConfig, NamerInitializer}
import io.l5d.fs.FsConfig

class fs extends NamerInitializer {
  val configClass = classOf[FsConfig]
  val configId = "io.l5d.fs"
}

object fs {
  case class FsConfig(rootDir: Directory) extends NamerConfig {
    @JsonIgnore
    override def defaultPrefix: Path = Path.read("/io.l5d.fs")

    /**
     * Construct a namer.
     */
    @JsonIgnore
    def newNamer(params: Stack.Params) = new WatchingNamer(rootDir.path, prefix)
  }
}
