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

// TODO: make experimental
case class ConfigMapInterpreterConfig(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String],
  name: String,
  filename: String
) extends InterpreterConfig with ClientConfig {

  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  private[this] val log = Logger()

  @JsonIgnore
  val nsOrDefault = namespace.getOrElse(ClientConfig.DefaultNamespace)

  @JsonIgnore
  val api = {
    val client = mkClient(Params.empty).configured(Label("configMapInterpreter"))
    Api(client.newService(dst))
  }

  @JsonIgnore
  val nsApi = api.withNamespace(nsOrDefault)

  @JsonIgnore
  val nsWatchApi = api.withNamespaceWatch(nsOrDefault)

  @JsonIgnore
  private[this] object Closed extends Throwable

  @JsonIgnore
  val stateWithClose = Var.async[Activity.State[Dtab]](Activity.Pending) { state =>
    val closeRef = new AtomicReference[Closable](Closable.nop)
    val pending = nsApi.configMap(name).get().respond {
      case Throw(e) => state.update(Activity.Failed(e))
      case Return(configMap) =>
        val initState = getDtab(configMap)
        state.update(Activity.Ok(initState))
        val (stream, close) = nsWatchApi.configMap(name).watch(None, None, None)
        closeRef.set(close)
        val _ = stream.foldLeft(initState) { (dtab, watchEvent) =>
          val newState: Dtab = watchEvent match {
            case ConfigMapAdded(a) => getDtab(a)
            case ConfigMapModified(m) => getDtab(m)
            case ConfigMapDeleted(_) => Dtab.empty
            case ConfigMapError(e) =>
              log.error("k8s watch error: %s", e)
              dtab
          }
          state.update(Activity.Ok(newState))
          newState
        }
    }

    Closable.make { t =>
      pending.raise(Closed)
      Closable.ref(closeRef).close(t)
    }
  }

  @JsonIgnore
  val act = Activity(stateWithClose)

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
