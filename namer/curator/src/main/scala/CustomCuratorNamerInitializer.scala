package io.buoyant.namer.curator

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path}
import com.twitter.finagle.Stack.Params
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class CustomCuratorNamerInitializer extends NamerInitializer {
  override def configId: String = "com.xoom.curator"

  override def configClass: Class[_] = {
    classOf[CustomCuratorNamerConfig]
  }
}

case class CustomCuratorNamerConfig(
  zookeeperConnectionString: String,
  baseZnodePath: String
) extends NamerConfig {

  @JsonIgnore
  override def defaultPrefix: Path = Path.Utf8("com.xoom.curator")

  @JsonIgnore // TODO Pass curator root as param.
  override def newNamer(params: Params): Namer = {
    new CustomCuratorNamer(zookeeperConnectionString, baseZnodePath)
  }
}

