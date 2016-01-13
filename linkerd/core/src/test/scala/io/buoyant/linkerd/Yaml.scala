package io.buoyant.linkerd

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

/**
 * Parser util
 *
 * We keep this in the test so that it's easy to write config blocks
 * without incurring a dependency on databind or dataformat-yaml from
 * linkerd-core.
 */
object Yaml {
  private[this] val factory = new YAMLFactory(new ObjectMapper)
  def apply(str: String): JsonParser = {
    val p = factory.createParser(str)
    p.nextToken()
    p
  }
}
