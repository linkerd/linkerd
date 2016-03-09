package io.buoyant.linkerd.config

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.{DeserializationContext, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.util.LoadService
import scala.reflect.{ClassTag, classTag}

private[config] trait Registration {
  def register(module: SimpleModule): SimpleModule
}

abstract class ConfigDeserializer[T: ClassTag] extends StdDeserializer[T](Parser.jClass[T]) with Registration {
  def register(module: SimpleModule): SimpleModule = module.addDeserializer(Parser.jClass[T], this)

  protected def catchMappingException(ctxt: DeserializationContext)(t: => T): T =
    try t catch {
      case arg: IllegalArgumentException =>
        throw ctxt.mappingException(arg.getMessage)
    }
}

abstract class ConfigSerializer[T: ClassTag] extends StdSerializer[T](Parser.jClass[T]) with Registration {
  def register(module: SimpleModule): SimpleModule = module.addSerializer(Parser.jClass[T], this)
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
  def objectMapper(
    config: String,
    configInitializers: Seq[ConfigInitializer]
  ): ObjectMapper with ScalaObjectMapper = {
    val factory = if (peekJsonObject(config)) new JsonFactory() else new YAMLFactory()
    objectMapper(factory, configInitializers)
  }

  def jsonObjectMapper(
    configInitializers: Seq[ConfigInitializer]
  ): ObjectMapper with ScalaObjectMapper = objectMapper(new JsonFactory(), configInitializers)

  private[this] def objectMapper(
    factory: JsonFactory,
    configInitializers: Seq[ConfigInitializer]
  ): ObjectMapper with ScalaObjectMapper = {
    val customTypes = (LoadService[ConfigDeserializer[_]] ++ LoadService[ConfigSerializer[_]])
      .foldLeft(new SimpleModule("linkerd custom types")) { (module, d) =>
        d.register(module)
      }

    val mapper = new ObjectMapper(factory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(customTypes)
    mapper.setSerializationInclusion(Include.NON_NULL)

    // Subtypes must not conflict
    configInitializers.groupBy(_.configId).collect {
      case (id, cis) if cis.size > 1 =>
        throw ConflictingSubtypes(cis(0).namedType, cis(1).namedType)
    }
    for (ci <- configInitializers) ci.registerSubtypes(mapper)

    mapper
  }
}
