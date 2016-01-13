package io.buoyant.linkerd

import java.io.InputStream
import java.util.Properties

/** Build metadata for a linker */
case class Build(version: String, revision: String, name: String)

object Build {
  val unknown = Build("?", "?", "?")

  def load(resource: String = "/io/buoyant/linkerd-core/build.properties"): Build =
    load(getClass.getResourceAsStream(resource))

  def load(stream: InputStream): Build =
    Option(stream) match {
      case None => unknown
      case Some(resource) =>
        val props = new Properties
        try props.load(resource) finally resource.close()
        Build(
          props.getProperty("version", "?"),
          props.getProperty("build_revision", "?"),
          props.getProperty("build_name", "?")
        )
    }
}
