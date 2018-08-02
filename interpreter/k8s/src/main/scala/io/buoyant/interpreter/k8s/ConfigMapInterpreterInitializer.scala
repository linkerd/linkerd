package io.buoyant.interpreter
package k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Service}
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.finagle.param.Label
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, WatchState, v1}
import io.buoyant.k8s.v1._
import io.buoyant.namer.{ConfiguredDtabNamer, InterpreterConfig, InterpreterInitializer, Param}

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
  private[this] val watch = new WatchState[v1.ConfigMap, v1.ConfigMapWatch]()

  @JsonIgnore
  private[this] val adminUri = "/interpreter_state/io.l5d.k8s.configmap.json"

  @JsonIgnore
  val api = {
    val client = mkClient(Params.empty).configured(Label("configMapInterpreter"))
    val nsOrDefault = namespace.getOrElse(DefaultNamespace)
    Api(client.newService(dst)).withNamespace(nsOrDefault)
  }

  @JsonIgnore
  val act = api.configMap(name)
    .activity(extractDtab, watchState = Some(watch)) {
      // TODO: if the event doesn't update our dtab, don't update the activity?
      case (oldDtab, ConfigMapAdded(newDtab)) =>
        getDtab(newDtab).getOrElse { oldDtab }
      case (oldDtab, ConfigMapModified(newDtab)) =>
        getDtab(newDtab).getOrElse { oldDtab }
      case (_, ConfigMapDeleted(_)) =>
        log.warning(s"k8s ConfigMap %s was deleted!", name)
        Dtab.empty
      case (oldDtab, ConfigMapError(status)) =>
        log.error("k8s watch error: %s", status)
        oldDtab
    }

  @JsonIgnore
  @inline
  private[this] def extractDtab(response: Option[ConfigMap]): Dtab =
    response.map(getDtab) match {
      case Some(dtab) => dtab.getOrElse { Dtab.empty }
      case None =>
        log.warning(s"K8s ConfigMap %s doesn't exist, assuming it will be created", name)
        Dtab.empty
    }

  @JsonIgnore
  def getDtab(configMap: ConfigMap): Try[Dtab] =
    configMap.data.get(filename) match {
      case None =>
        log.warning(
          s"dtab at %s in k8s ConfigMap %s did not exist!",
          filename,
          name
        )
        Return(Dtab.empty)
      case Some(data) =>
        Try(Dtab.read(data)).onFailure {
          case e: IllegalArgumentException =>
            log.error(
              "k8s ConfigMap %s error reading dtab '%s': %s",
              name,
              filename,
              e
            )
        }
    }

  @JsonIgnore
  private[this] val watchStateHandler = new Service[Request, Response] {
    val mapper = Parser.jsonObjectMapper(Nil)

    override def apply(request: Request): Future[Response] = {
      val rep = Response(Status.Ok)
      val watchState = Map(
        "state" -> act.stateSnapshot(),
        "watch" -> watch
      )

      rep.contentString = mapper.writeValueAsString(watchState)
      rep.mediaType = MediaType.Json
      Future.value(rep)
    }
  }

  private[this] val handlers = Seq(Admin.Handler(adminUri, watchStateHandler))

  @JsonIgnore
  override def newInterpreter(params: Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredDtabNamer(
      act.underlying,
      namers,
      handlers
    )
  }
}
