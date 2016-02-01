package io.buoyant.linkerd.config

import cats.data.{Validated, ValidatedNel}
import cats.data.Validated._
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.config.LinkerConfig.Impl

trait ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit
}

/**
 *
 * @param parsedConfig A representation of the configuration file as it was provided, without any defaults applied.
 *                     Will be None if we were unable to parse the file at all due to a syntax error.
 * @param validatedConfig Either a list of [[ConfigError]]s representing problems validating the configuration, or a
 *               [[LinkerConfig.Validated]] object which has been validated to initialize a linker.
 *
 */
case class ParseResult(
  parsedConfig: Option[LinkerConfig],
  validatedConfig: ValidatedNel[ConfigError, LinkerConfig.Validated]
)

object Parser {
  def apply(s: String): ParseResult = {
    val baseCfg: ValidatedNel[ConfigError, Impl] = Validated.catchNonFatal {
      objectMapper(s).readValue[LinkerConfig.Impl](s)
    }.bimap(
      ConfigError.transform,
      identity
    ).toValidatedNel

    ParseResult(
      baseCfg.toOption,
      baseCfg.fold(
        invalid,
        { _.validated }
      )
    )
  }

  private[this] def peekJsonObject(s: String): Boolean =
    s.dropWhile(_.isWhitespace).headOption == Some('{')

  /**
   * Load a Json or Yaml parser, depending on whether the content appears to be Json.
   */
  private[this] def objectMapper(config: String): ObjectMapper with ScalaObjectMapper = {
    val factory = if (peekJsonObject(config)) new JsonFactory() else new YAMLFactory()
    val mapper = new ObjectMapper(factory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    LoadService[ConfigRegistrar]() foreach { _.register(mapper) }
    mapper
  }
}

