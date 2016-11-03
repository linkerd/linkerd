package io.buoyant.namer.curator

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path}
import com.twitter.finagle.Stack.Params
import io.buoyant.config.types.{HostAndPort, Port}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class CuratorInitializer extends NamerInitializer {
  override def configId = "io.l5d.curator"
  override def configClass = classOf[CuratorConfig]
}

object CuratorInitializer extends CuratorInitializer

case class CuratorConfig(
  zkAddrs: Seq[HostAndPort],
  basePath: Option[String]
) extends NamerConfig {

  override def experimentalRequired = true

  @JsonIgnore
  override def defaultPrefix: Path = Path.Utf8("io.l5d.curator")

  @JsonIgnore
  val connectString = zkAddrs.map(_.toString(defaultPort = Port(2181))).mkString(",")

  @JsonIgnore
  override def newNamer(params: Params): Namer = {
    new CuratorNamer(connectString, basePath.getOrElse("/"), prefix)
  }
}
