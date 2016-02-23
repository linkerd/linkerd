package io.buoyant.linkerd.config

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.{DeserializationContext, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.util.LoadService
import scala.reflect.{ClassTag, classTag}

abstract class ConfigDeserializer[T: ClassTag] extends StdDeserializer[T](Parser.jClass[T]) {
  def register(module: SimpleModule): SimpleModule = {
    module.addDeserializer(Parser.jClass[T], this)
  }
  protected def catchMappingException(ctxt: DeserializationContext)(t: => T): T =
    try t catch {
      case arg: IllegalArgumentException =>
        throw ctxt.mappingException(arg.getMessage)
    }
}

object Parser {

  private[config] def jClass[T: ClassTag] = classTag[T].runtimeClass.asInstanceOf[Class[T]]

  private[this] def peekJsonObject(s: String): Boolean =
    s.dropWhile(_.isWhitespace).startsWith("{")

  /**
   * Load a Json or Yaml parser, depending on whether the content appears to
   * be Json. We expose this publicly for testing purposes (to allow easy
   * parsing of config subtrees) at the moment.
   */
  def objectMapper(config: String): ObjectMapper with ScalaObjectMapper = {
    val factory = if (peekJsonObject(config)) new JsonFactory() else new YAMLFactory()
    val customTypes = LoadService[ConfigDeserializer[_]]
      .foldLeft(new SimpleModule("linkerd custom types")) { (module, d) =>
        d.register(module)
      }

    val mapper = new ObjectMapper(factory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(customTypes)
    mapper
  }
}
