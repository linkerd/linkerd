package io.buoyant.k8s

import com.twitter.util.{Activity, Updatable, Var}

trait Stabilize {
  protected type VarUp[T] = Var[T] with Updatable[T]

  /**
   * We can stabilize this by changing the type to Var[Option[Var[T]]].
   * If this Option changes from None to Some or vice versa, the outer Var will
   * update.  If the value contained in the Some changes, only the inner Var
   * will update.
   */
  protected def stabilize[T](unstable: Var[Option[T]]): Var[Option[Var[T]]] = {
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

  protected def stabilize[T](unstable: Activity[Option[T]]): Activity[Option[Var[T]]] = {
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
