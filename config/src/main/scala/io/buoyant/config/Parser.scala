package io.buoyant.config

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.{JsonFactory, JsonParser}
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
    inits: Iterable[Seq[ConfigInitializer]]
  ): ObjectMapper with ScalaObjectMapper = {
    if (peekJsonObject(config)) jsonObjectMapper(inits)
    else yamlObjectMapper(inits)
  }

  def jsonObjectMapper(
    configInitializers: Iterable[Seq[ConfigInitializer]]
  ): ObjectMapper with ScalaObjectMapper =
    objectMapper(new JsonFactory, configInitializers)

  def yamlObjectMapper(
    configInitializers: Iterable[Seq[ConfigInitializer]]
  ): ObjectMapper with ScalaObjectMapper =
    objectMapper(new YAMLFactory, configInitializers)

  private[this] def objectMapper(
    factory: JsonFactory,
    configInitializers: Iterable[Seq[ConfigInitializer]]
  ): ObjectMapper with ScalaObjectMapper = {
    def ensureUniqueKinds(inits: Seq[ConfigInitializer]): Unit =
      inits.groupBy(_.configId).foreach {
        case (_, Seq(a, b, _*)) => throw ConflictingSubtypes(a.namedType, b.namedType)
        case _ =>
      }

    val customTypes = (LoadService[ConfigDeserializer[_]] ++ LoadService[ConfigSerializer[_]])
      .foldLeft(new SimpleModule("linkerd custom types")) { (module, d) =>
        d.register(module)
      }

    val mapper = new ObjectMapper(factory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(customTypes)
    mapper.setSerializationInclusion(Include.NON_ABSENT)
    mapper.setVisibility(PropertyAccessor.ALL, Visibility.PUBLIC_ONLY)
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    // Subtypes with the same config deserializer parent must not conflict
    for (kinds <- configInitializers) {
      kinds.groupBy(_.configClass.getSuperclass).values.foreach(ensureUniqueKinds)
      for (k <- kinds) k.registerSubtypes(mapper)
    }

    mapper
  }
}
