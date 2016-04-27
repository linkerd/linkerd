package io.buoyant

import com.twitter.util.{Future, Activity}

package object namerd {
  type Ns = String

  implicit class RichActivity[T](val activity: Activity[T]) extends AnyVal {
    /** A Future representing the first non-pending value of this Activity */
    def toFuture: Future[T] = activity.values.toFuture.flatMap(Future.const)
  }
}
