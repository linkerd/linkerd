package io.buoyant.interpreter
package k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Dtab
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param.Label
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.config.types.Port
import io.buoyant.k8s.ClientConfig
import io.buoyant.k8s.v1._
import io.buoyant.namer.{ConfiguredDtabNamer, InterpreterConfig, InterpreterInitializer, Param}
import java.util.concurrent.atomic.AtomicReference

class ConfigMapInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[ConfigMapInterpreterConfig]
  override def configId: String = "io.l5d.k8s.configMap"
}

object ConfigMapInterpreterInitializer extends ConfigMapInterpreterInitializer

case class ConfigMapInterpreterConfig(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String],
  name: String,
  filename: String
) extends InterpreterConfig with ClientConfig {

  @JsonIgnore
  override def experimentalRequired = true

  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  private[this] val log = Logger()

  @JsonIgnore
  val nsOrDefault = namespace.getOrElse(DefaultNamespace)

  @JsonIgnore
  val api = {
    val client = mkClient(Params.empty).configured(Label("configMapInterpreter"))
    Api(client.newService(dst))
  }

  @JsonIgnore
  val nsApi = api.withNamespace(nsOrDefault)

  @JsonIgnore
  val act = nsApi.configMap(name)
    .activity(getDtab, resourceVersion = false) {
      (dtab, event) =>
        event match {
          case ConfigMapAdded(a) => getDtab(a)
          case ConfigMapModified(m) => getDtab(m)
          case ConfigMapDeleted(_) => Dtab.empty
          case ConfigMapError(e) =>
            log.error("k8s watch error: %s", e)
            dtab
        }
    }

  @JsonIgnore
  def getDtab(configMap: ConfigMap): Dtab =
    configMap.data.get(filename) match {
      case None => Dtab.empty
      case Some(data) => Dtab.read(data)
    }

  @JsonIgnore
  override def newInterpreter(params: Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredDtabNamer(act, namers)
  }
}
