package io.buoyant.linkerd.config

import cats.data.{NonEmptyList, ValidatedNel}
import cats.data.Validated._
import cats.implicits._
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.twitter.finagle.Path

/**
 * Read a single namer configuration in the form:
 *
 * <pre>
 *   kind: io.l5d.izzle
 *   prefix: /i
 *   frizzle: dee
 *   swizzle: dah
 * </pre>
 *
 * In this example _io.l5d.izzle_ must be the _kind_ of a
 * [[NamerConfig]] in `namers`.  _frizzle_ and _swizzle_ are
 * namer-specific options.  This namer refines names beginning with
 * `/i` (after this prefix has been stripped).
 */
// TODO: switch to using class names once we have fully replaced the existing system.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait NamerConfig {
  var prefix: Option[String] = None

  def withDefaults(linker: LinkerConfig): NamerConfig.Defaults =
    new NamerConfig.Defaults(this, protocol, linker)

  def protocol: NamerProtocol
}

trait NamerProtocol {
  def kind: String
  def validated: ValidatedNel[ConfigError, NamerProtocol]
  def defaultPrefix: Option[String] = Some(Path.Utf8(kind).show)
}

object NamerConfig {
  class Defaults(base: NamerConfig, protocol: NamerProtocol, linker: LinkerConfig) {
    def prefix: Option[String] = base.prefix orElse protocol.defaultPrefix
    def validated: ValidatedNel[ConfigError, NamerConfig.Validated] = {
      def validatedPrefix: ValidatedNel[ConfigError, Path] = {
        prefix.fold(invalidNel[ConfigError, Path](MissingPath)) { pathStr =>
          catchOnly[IllegalArgumentException] {
            Path.read(pathStr)
          }.leftMap { ex =>
            NonEmptyList(InvalidPath(pathStr, ex))
          }
        }
      }
      (validatedPrefix |@|
        protocol.validated).map {
          case (prefix, protocol) => new Validated(prefix, protocol)
        }
    }
  }

  class Validated(val prefix: Path, val protocol: NamerProtocol)
}
