package io.buoyant

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Service, http => fhttp}
import com.twitter.logging.Logger
import com.twitter.util.{Activity, Updatable, Var}
import io.buoyant.config.JsonStreamParser

/**
 * This package contains representations of objects returned by multiple versions of the Kubernetes
 * API. Version-specific objects should go in sub-packages (see v1.scala).
 */
package object k8s {
  type Client = Service[fhttp.Request, fhttp.Response]

  private[k8s] val log = Logger.get("k8s")

  val Json = {
    val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    LoadService[SerializationModule].foreach { svc => mapper.registerModule(svc.module) }
    new JsonStreamParser(mapper)
  }

  type VarUp[T] = Var[T] with Updatable[T]

  /**
   * We can stabilize this by changing the type to Var[Option[Var[T]]].
   * If this Option changes from None to Some or vice versa, the outer Var will
   * update.  If the value contained in the Some changes, only the inner Var
   * will update.
   */
  def stabilize[T](unstable: Var[Option[T]]): Var[Option[Var[T]]] = {
    val init = unstable.sample().map(Var(_))
    Var.async[Option[VarUp[T]]](init) { update =>
      // the current inner Var, null if the outer Var is None
      @volatile var current: VarUp[T] = null

      unstable.changes.respond {
        case Some(t) if current == null =>
          // T created
          current = Var(t)
          update() = Some(current)
        case Some(t) =>
          // T modified
          current() = t
        case None =>
          // T deleted
          current = null
          update() = None
      }
    }
  }

  // NOTE: we may want to consider naming this function, since there is also
  //       a method `Activity.stabilize()` on `Activity` which has completely
  //       different behaviour. This seems confusing.
  //
  //       N.B. that if we renamed this function, we probably would want to
  //       also rename the corresponding `stabilize(Var)` function that was
  //       the source of this name in the first place...
  def stabilize[T](unstable: Activity[Option[T]]): Activity[Option[Var[T]]] = {
    val inner = Var.async[Activity.State[Option[VarUp[T]]]](Activity.Pending) { update =>
      // the current inner Var, null if the outer Var is None
      @volatile var current: VarUp[T] = null

      unstable.run.changes.respond {
        case Activity.Ok(Some(t)) if current == null =>
          // T created
          current = Var(t)
          update() = Activity.Ok(Some(current))
        case Activity.Ok(Some(t)) =>
          // T modified
          current() = t
        case Activity.Ok(None) =>
          // T deleted
          current = null
          update() = Activity.Ok(None)
        case Activity.Pending =>
          update() = Activity.Pending
      }
    }
    Activity(inner)
  }
}
