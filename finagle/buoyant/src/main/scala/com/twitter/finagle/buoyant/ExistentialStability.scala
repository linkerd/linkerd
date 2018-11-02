package com.twitter.finagle.buoyant

import com.twitter.logging.Logger
import com.twitter.util._

/**
 * Extensions to `Var`/`Activity` allowing a `Var` containing an `Option` to be split
 * into an inner Var and outer Var, where the outer Var tracks the existence of the
 * Option and the inner Var tracks changes to the contained object.
 */
object ExistentialStability {

  type VarUp[T] = Var[T] with Updatable[T]

  val logger = Logger()

  implicit class ExistentialVar[T <: AnyRef](val unstable: Var[Option[T]])
    extends AnyVal {
    /**
     * We can stabilize this by changing the type to Var[Option[Var[T]]].
     * If this Option changes from None to Some or vice versa, the outer Var will
     * update.  If the value contained in the Some changes, only the inner Var
     * will update.
     */
    def stabilizeExistence: Var[Option[Var[T]]] = {

      val init = unstable.sample()
      val initT = init.getOrElse(null.asInstanceOf[T])

      val inner: Var[T] = Var.async(initT) { update =>
        unstable.changes.respond {
          case Some(t) => update() = t
          case _ =>
        }
      }

      val initOuter = init.map(_ => inner)
      Var.async[Option[Var[T]]](initOuter) { update =>
        unstable.changes.respond {
          case Some(_) =>
            update() = Some(inner)
          case None =>
            update() = None
        }
      }
    }
  }

  implicit class ExistentialAct[T <: AnyRef](val unstable: Activity[Option[T]])
    extends AnyVal {
    def stabilizeExistence: Activity[Option[Var[T]]] = {

      val inner: Var[T] = Var.async(null.asInstanceOf[T]) { update =>
        unstable.states.respond {
          case Activity.Ok(Some(t)) => update() = t
          case _ =>
        }
      }

      val outer = Var.async[Activity.State[Option[Var[T]]]](Activity.Pending) { update =>
        val closable = unstable.states.respond {
          case Activity.Ok(Some(_)) =>
            update() = Activity.Ok(Some(inner))
          case Activity.Ok(None) =>
            update() = Activity.Ok(None)
          case Activity.Pending =>
            update() = Activity.Pending
          case Activity.Failed(e) =>
            update() = Activity.Failed(e)
        }
        Closable.all(closable, Closable.make { _ =>
          Future.Unit
        })
      }

      Activity(outer)
    }
  }
}
