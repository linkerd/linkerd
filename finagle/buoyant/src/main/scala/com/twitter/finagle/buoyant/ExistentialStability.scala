package com.twitter.finagle.buoyant

import com.twitter.util._

/**
 * Extensions to `Var`/`Activity` allowing a `Var` containing an `Option` to be split
 * into an inner Var and outer Var, where the outer Var tracks the existence of the
 * Option and the inner Var tracks changes to the contained object.
 */
object ExistentialStability {

  type VarUp[T] = Var[T] with Updatable[T]

  implicit class ExistentialVar[T <: AnyRef](val unstable: Var[Option[T]])
    extends AnyVal {
    /**
     * We can stabilize this by changing the type to Var[Option[Var[T]]].
     * If this Option changes from None to Some or vice versa, the outer Var will
     * update.  If the value contained in the Some changes, only the inner Var
     * will update.
     */
    def stabilizeExistence: Var[Option[Var[T]]] = {

      // The inner Var is null until the first time unstable becomes a Some.
      @volatile var inner: Var[T] = null

      def makeInner(init: T) = Var.async(init) { update =>
        unstable.changes.respond {
          case Some(t) => update() = t
          case _ =>
        }
      }

      // Initial value for outer Var.
      val init = unstable.sample().map { t =>
        if (inner == null) {
          inner = makeInner(t)
        }
        inner
      }

      @volatile var exists = init.isDefined
      val mu = new {}

      Var.async[Option[Var[T]]](init) { update =>
        unstable.changes.respond { state =>
          mu.synchronized {
            state match {
              case Some(_) if exists =>
              // Do nothing.
              case Some(t) if !exists =>
                exists = true
                if (inner == null) {
                  inner = makeInner(t)
                }
                update() = Some(inner)
              case None if !exists =>
              // Do nothing.
              case None if exists =>
                exists = false
                update() = None
            }
          }
        }
      }
    }
  }

  implicit class ExistentialAct[T](val unstable: Activity[Option[T]])
    extends AnyVal {
    def stabilizeExistence: Activity[Option[Var[T]]] = {

      // The inner Var is null until the first time unstable becomes a Some.
      @volatile var inner: Var[T] = null

      // This keeps track of the state of the outer Var:
      // None means the state was not Activity.Ok
      // Some(false) means the state was Activity.Ok(Some)
      // Some(true) means the state was Activity.Ok(None)
      //
      // We need to track these three states because we only want to update the outer Var when we
      // transition between states.
      @volatile var exists: Option[Boolean] = None
      val mu = new {}

      val outer = Var.async[Activity.State[Option[Var[T]]]](Activity.Pending) { update =>
        unstable.states.respond { state =>
          mu.synchronized {
            state match {
              case Activity.Ok(Some(_)) if exists == Some(true) =>
              // Do nothing.
              case Activity.Ok(Some(t)) if exists != Some(true) =>
                exists = Some(true)
                if (inner == null) {
                  inner = Var.async(t) { update =>
                    unstable.states.respond {
                      case Activity.Ok(Some(tt)) => update() = tt
                      case _ =>
                    }
                  }
                }
                update() = Activity.Ok(Some(inner))
              case Activity.Ok(None) if exists == Some(false) =>
              // Do nothing.
              case Activity.Ok(None) if exists != Some(false) =>
                exists = Some(false)
                update() = Activity.Ok(None)
              case Activity.Pending =>
                exists = None
                update() = Activity.Pending
              case Activity.Failed(e) =>
                exists = None
                update() = Activity.Failed(e)
            }
          }
        }
      }

      Activity(outer)
    }
  }
}
