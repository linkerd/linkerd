package io.buoyant.linkerd.config.namers

import cats.data.ValidatedNel
import cats.data.Validated._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType

import io.buoyant.linkerd.config._
import java.nio.file.{Path => NioPath, InvalidPathException, Paths}

/**
 * Created by greg on 2/2/16.
 */
case class FileSystemNamerConfig(rootDir: Option[String]) extends NamerConfig {
  def protocol = FileSystemNamerConfig.Protocol(rootDir)
}

object FileSystemNamerConfig {
  object Protocol {
    def kind = "io.l5d.fs" // TODO: switch to using the actual class name once we can avoid conflicts with existing system
  }

  trait FsProtocol extends NamerProtocol {
    def kind = Protocol.kind
  }

  case class Protocol(rootDir: Option[String]) extends FsProtocol {
    def validated: ValidatedNel[ConfigError, ValidatedProtocol] = {
      def validatedPath: ValidatedNel[ConfigError, NioPath] =
        rootDir.fold(invalidNel[ConfigError, NioPath](MissingRootDir)) { pathStr =>
          try {
            val path = Paths.get(pathStr)
            if (path.toFile.isDirectory) valid(path) else invalidNel(RootDirNotDirectory(path))
          } catch {
            case ex: InvalidPathException => invalidNel(InvalidPath(pathStr, ex))
          }
        }

      validatedPath.map(ValidatedProtocol(_))
    }
  }

  case class ValidatedProtocol(rootDir: NioPath) extends FsProtocol {
    def validated = valid(this)
  }
}

// This is temporary! Eventually we will use classpaths for NamerConfigs.
class FileSystemNamerConfigRegistrar extends ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit =
    mapper.registerSubtypes(new NamedType(classOf[FileSystemNamerConfig], FileSystemNamerConfig.Protocol.kind))
}
