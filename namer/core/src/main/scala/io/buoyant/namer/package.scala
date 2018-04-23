package io.buoyant

import com.twitter.util.{Activity, Future, Var}

package object namer {
  implicit class RichActivity[T](val activity: Activity[T]) extends AnyVal {
    /** A Future representing the first non-pending value of this Activity */
    def toFuture: Future[T] = activity.values.toFuture.flatMap(Future.const)

    def dedup: Activity[T] = Activity(Var.async[Activity.State[T]](Activity.Pending) { up =>
      activity.states.dedup.respond(up.update)
    })
  }
}
