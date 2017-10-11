package com.twitter.finagle.buoyant

import com.twitter.util.{Activity, Updatable, Var}

/**
 * Extensions to `Var`/`Activity` allowing a `Var` containing an `Option` to be split
 * into an inner Var and outer Var, where the outer Var tracks the existence of the
 * Option and the inner Var tracks changes to the contained object.
 */
object ExistentialStability {

  type VarUp[T] = Var[T] with Updatable[T]

  implicit class ExistentialVar[T](val unstable: Var[Option[T]])
    extends AnyVal {
    /**
     * We can stabilize this by changing the type to Var[Option[Var[T]]].
     * If this Option changes from None to Some or vice versa, the outer Var will
     * update.  If the value contained in the Some changes, only the inner Var
     * will update.
     */
    def stabilizeExistence: Var[Option[Var[T]]] = {
      val init = unstable.sample().map(Var(_))
      Var.async[Option[VarUp[T]]](init) { update =>
        // the current inner Var, null if the outer Var is None
        @volatile var current: VarUp[T] = null
        @volatile var exists = false
        unstable.changes.respond {
          case Some(t) if current == null =>
            // T created
            exists = true
            current = Var(t)
            update() = Some(current)
          case Some(t) if !exists =>
            // T re-created
            exists = true
            current() = t
            update() = Some(current)
          case Some(t) =>
            // T modified
            current() = t
          case None =>
            // T deleted
            exists = false
            update() = None
        }
      }
    }
  }

  implicit class ExistentialAct[T](val unstable: Activity[Option[T]])
    extends AnyVal {
    def stabilizeExistence: Activity[Option[Var[T]]] = {
      val inner = Var.async[Activity.State[Option[VarUp[T]]]](Activity.Pending) { update =>
        // the current inner Var, null if the outer Var is None
        @volatile var current: VarUp[T] = null
        @volatile var exists = false
        unstable.states.respond {
          case Activity.Ok(Some(t)) if current == null =>
            // T created
            current = Var(t)
            exists = true
            update() = Activity.Ok(Some(current))
          case Activity.Ok(Some(t)) if !exists =>
            // T recreated
            exists = true
            current() = t
            update() = Activity.Ok(Some(current))
          case Activity.Ok(Some(t)) =>
            // T modified
            current() = t
          case Activity.Ok(None) =>
            // T deleted
            exists = false
            update() = Activity.Ok(None)
          case Activity.Pending =>
            update() = Activity.Pending
        }
      }
      Activity(inner)
    }
  }
}
