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
  require(name != null, "ConfigMap name is required!")
  require(filename != null, "ConfigMap dtab filename is required!")

  @JsonIgnore
  override def experimentalRequired = true

  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  private[this] val log = Logger()

  @JsonIgnore
  val api = {
    val client = mkClient(Params.empty).configured(Label("configMapInterpreter"))
    val nsOrDefault = namespace.getOrElse(DefaultNamespace)
    Api(client.newService(dst)).withNamespace(nsOrDefault)
  }

  @JsonIgnore
  val act = api.configMap(name)
    .activity(extractDtab) {
      (dtab, event) =>
        event match {
          case ConfigMapAdded(a) => getDtab(a)
          case ConfigMapModified(m) => getDtab(m)
          case ConfigMapDeleted(_) =>
            log.warning(s"k8s ConfigMap $name was deleted!")
            Dtab.empty
          case ConfigMapError(e) =>
            log.error("k8s watch error: %s", e)
            dtab
        }
    }

  @JsonIgnore
  @inline
  private[this] def extractDtab(response: Option[ConfigMap]): Dtab =
    response.map(getDtab).getOrElse {
      log.warning(s"K8s ConfigMap %s doesn't exist, assuming it will be created", name)
      Dtab.empty
    }

  @JsonIgnore
  def getDtab(configMap: ConfigMap): Dtab =
    configMap.data.get(filename) match {
      case None =>
        log.warning(s"dtab at %s in k8s ConfigMap %s did not exist!", filename, name)
        Dtab.empty
      case Some(data) => Dtab.read(data)
    }

  @JsonIgnore
  override def newInterpreter(params: Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredDtabNamer(act, namers)
  }
}
