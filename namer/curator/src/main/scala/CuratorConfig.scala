package io.buoyant.namer.curator

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path}
import com.twitter.finagle.Stack.Params
import io.buoyant.namer.NamerConfig

case class CuratorConfig(
  zookeeperConnectionString: String,
  baseZnodePath: String
) extends NamerConfig {

  @JsonIgnore
  override def defaultPrefix: Path = Path.Utf8("io.l5d.curator")

  @JsonIgnore // TODO Pass curator root as param.
  override def newNamer(params: Params): Namer = {
    new CuratorNamer(zookeeperConnectionString, baseZnodePath)
  }
}
