package com.twitter.finagle.buoyant

import com.twitter.util._

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

      // The inner Var is null until the first time unstable becomes a Some.
      @volatile var inner: Var[T] = null

      def makeInner(init: T) = Var.async(init) { update =>
        unstable.changes.respond {
          case Some(t) => update() = t
          case _ =>
        }
      }

      var exists = false
      val mu = new {}

      Var.async[Option[Var[T]]](None) { update =>
        unstable.changes.respond { state =>
          mu.synchronized {
            state match {
              case Some(t) if inner == null =>
                // T created.
                inner = makeInner(t)
                exists = true
                update() = Some(inner)
              case Some(_) if !exists =>
                // T re-created.
                update() = Some(inner)
              case Some(_) if exists =>
              // T modified.
              // Existence has not changed so the outer Var should not be updated.
              case None if exists =>
                // T deleted.
                exists = false
                update() = None
              case None if !exists =>
              // Spurious update in None state.  We can just ignore it.
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

      def makeInner(init: T) = Var.async(init) { update =>
        unstable.states.respond {
          case Activity.Ok(Some(tt)) => update() = tt
          case _ =>
        }
      }

      // This keeps track of the state of the outer Var.
      // We need to track these three states because we only want to update the outer Var when we
      // transition between states.
      var exists: State = NotOk

      val mu = new {}

      val outer = Var.async[Activity.State[Option[Var[T]]]](Activity.Pending) { update =>
        unstable.states.respond { state =>
          mu.synchronized {
            state match {
              case Activity.Ok(Some(t)) if inner == null =>
                // T created.
                inner = makeInner(t)
                exists = DoesExist
                update() = Activity.Ok(Some(inner))
              case Activity.Ok(Some(_)) if exists != DoesExist =>
                // T re-created.
                exists = DoesExist
                update() = Activity.Ok(Some(inner))
              case Activity.Ok(Some(_)) if exists == DoesExist =>
              // T modified.
              // Existence has not changed so the outer Var should not be updated.
              case Activity.Ok(None) if exists != DoesNotExist =>
                // T deleted.
                exists = DoesNotExist
                update() = Activity.Ok(None)
              case Activity.Ok(None) if exists == DoesNotExist =>
              // Spurious update in None state.  We can just ignore it.
              case Activity.Pending =>
                // Pending.
                exists = NotOk
                update() = Activity.Pending
              case Activity.Failed(e) =>
                // Failed.
                exists = NotOk
                update() = Activity.Failed(e)
            }
          }
        }
      }

      Activity(outer)
    }
  }
}

sealed trait State
object DoesExist extends State
object DoesNotExist extends State
object NotOk extends State
