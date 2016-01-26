package io.buoyant.linkerd.config

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.util.LoadService

trait ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit
}

object Parser {
  // TODO: should we distinguish types between the without/with defaults configs?
  def apply(s: String): Either[Seq[ConfigError], (LinkerConfig, LinkerConfig)] = {
    val baseCfg = objectMapper(s).readValue[LinkerConfig.Impl](s)
    val defaultedCfg = baseCfg.withDefaults
    val validationFailures = defaultedCfg.validate
    if (validationFailures.isEmpty) Right(baseCfg, defaultedCfg) else Left(validationFailures)
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

