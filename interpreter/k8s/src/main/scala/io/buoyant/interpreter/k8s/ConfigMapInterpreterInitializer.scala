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

/**
  * Configuration for a `ConfigMap` Interpreter
  * @param host the hostname of the Kubernetes API endpoint from which to read
  *             the ConfigMap. Defaults to `localhost` if not provided (as per
  *             [[ClientConfig]]).
  * @param port the port on `host` that serves the Kubernetes API. defaults to
  *             `8001` (as per [[ClientConfig]]).
  * @param namespace the namespace containing the `ConfigMap`. defaults to
  *                  `default` (once again, as per [[ClientConfig]]).
  * @param name the name of the `ConfigMap`. must be provided.
  * @param filename the key within the `ConfigMap` corresponding to the dtab
  *                 to read from. must be provided.
  */
case class ConfigMapInterpreterConfig(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String],
  name: String,
  filename: String
) extends InterpreterConfig with ClientConfig {
  require(filename != null, "dtab 'filename' field is required")
  require(name != null, "ConfigMap 'name' field is required")

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
    .activity(getDtab, resourceVersion = false) {
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
  def getDtab(configMap: ConfigMap): Dtab =
    configMap.data.get(filename) match {
      case None =>
        log.warning(s"dtab at $filename in k8s ConfigMap $name did not exist!")
        Dtab.empty
      case Some(data) => Dtab.read(data)
    }

  @JsonIgnore
  override def newInterpreter(params: Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredDtabNamer(act, namers)
  }
}
