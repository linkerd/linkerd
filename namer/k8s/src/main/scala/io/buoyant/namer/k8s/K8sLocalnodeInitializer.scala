package io.buoyant.namer.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.{Namer, Path}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class K8sLocalnodeConfig extends NamerConfig {
  @JsonIgnore
  override val defaultPrefix = Path.read("/#/io.l5d.k8s.localnode")

  @JsonIgnore
  override protected def newNamer(params: Params): Namer = {
    val nodeName = sys.env.getOrElse(
      "NODE_NAME",
      throw new IllegalArgumentException(
        "NODE_NAME env variable must be set to the node's name"
      )
    )

    new K8sLocalnodeNamer(prefix, nodeName)
  }
}

class K8sLocalnodeInitializer extends NamerInitializer {
  override val configId = "io.l5d.k8s.localnode"
  val configClass = classOf[K8sLocalnodeConfig]
}
