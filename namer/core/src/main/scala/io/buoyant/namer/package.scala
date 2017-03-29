package io.buoyant

import com.twitter.util.{Activity, Future, Var}

package object namer {
  implicit class RichActivity[T](val activity: Activity[T]) extends AnyVal {
    /** A Future representing the first non-pending value of this Activity */
    def toFuture: Future[T] = activity.values.toFuture.flatMap(Future.const)

    def dedup: Activity[T] = {
      val stateVar = Var(Activity.Pending, activity.states.dedup)
      new Activity(stateVar)
    }
  }
}
