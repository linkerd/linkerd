package io.buoyant.test

import com.twitter.util.Activity
import org.scalatest.exceptions.TestFailedException

/**
 * Like TryValues but for Activity.
 */
trait ActivityValues {

  implicit class Valuable[T](state: Activity.State[T]) extends AnyRef {
    def ok: T = state match {
      case Activity.Ok(t) => t
      case Activity.Failed(e) =>
        throw new TestFailedException(s"expected Activity.Ok, found Activity.Failed($e)", e, 1)
      case Activity.Pending =>
        throw new TestFailedException(s"expected Activity.Ok, found Activity.Pending", 1)
    }

    def failed: Throwable = state match {
      case Activity.Ok(t) =>
        throw new TestFailedException(s"expected Activity.Failed, found Activity.Ok($t)", 1)
      case Activity.Failed(e) => e
      case Activity.Pending =>
        throw new TestFailedException(s"expected Activity.Failed, found Activity.Pending", 1)
    }

    def pending = state match {
      case Activity.Ok(t) =>
        throw new TestFailedException(s"expected Activity.Pending, found Activity.Ok($t)", 1)
      case Activity.Failed(e) =>
        throw new TestFailedException(s"expected Activity.Ok, found Activity.Failed($e)", e, 1)
      case p @ Activity.Pending => p
    }
  }

}
