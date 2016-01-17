package io.l5d

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.twitter.finagle.{Path, Stack}
import io.buoyant.linkerd.{NamerInitializer, Parsing}
import io.buoyant.linkerd.namer.fs.WatchingNamer
import java.nio.file.{Path => NioPath, Paths}

object fs {
  case class RootDir(path: Option[NioPath])
  implicit object RootDir extends Stack.Param[RootDir] {
    val default = RootDir(None)

    val parser = Parsing.Param("rootDir") { (p, params) =>
      Parsing.ensureTok(p, JsonToken.VALUE_STRING) { p =>
        val path = Paths.get(p.getText)
        p.nextToken()
        params + RootDir(Some(path))
      }
    }
  }

  val parser = RootDir.parser

  val defaultParams = Stack.Params.empty +
    NamerInitializer.Prefix(Path.Utf8("io.l5d.fs"))
}

class fs(val params: Stack.Params) extends NamerInitializer {
  def this() = this(fs.defaultParams)
  def withParams(ps: Stack.Params) = new fs(ps)

  def paramKeys = fs.parser.keys
  def readParam(k: String, p: JsonParser) =
    withParams(fs.parser.read(k, p, params))

  def newNamer() = params[fs.RootDir] match {
    case fs.RootDir(None) => throw new IllegalArgumentException("io.l5d.fs requires a 'rootDir'")
    case fs.RootDir(Some(path)) if !path.toFile.isDirectory =>
      throw new IllegalArgumentException(s"io.l5d.fs 'rootDir' is not a directory: $path")
    case fs.RootDir(Some(path)) => new WatchingNamer(path, params[NamerInitializer.Prefix].path)
  }
}
