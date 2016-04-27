package io.buoyant

import com.twitter.util._

package object namerd {
  type Ns = String

  implicit class RichActivity[T](val activity: Activity[T]) extends AnyVal {
    /** A Future representing the first non-pending value of this Activity */
    def toFuture: Future[T] = activity.values.toFuture.flatMap(Future.const)
  }

  implicit class RichEvent[T <: AnyRef](val event: Event[T]) extends AnyVal {
    /** An Event which excludes consecutive duplicate values */
    def dedup: Event[T] = new Event[T] {
      override def register(s: Witness[T]): Closable = {
        var current: T = null.asInstanceOf[T] // scala fails to prove Null <: T for some reason
        event.respond { t =>
          if (t != current) {
            current = t
            s.notify(t)
          }
        }
      }
    }
  }
}
