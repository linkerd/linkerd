package io.buoyant.test

import com.twitter.util.Activity
import org.scalatest.exceptions.TestFailedException

/**
 * Like ScalaTest's <code>TryValues</code> trait, but for <code>Activity.State</code>s.
 *
 * Trait that provides an implicit conversion that adds <code>ok</code>, <code>failed</code>, and <code>pending</code>
 * methods to <code>com.twitter.util.Activity.State</code>, enabling you to make assertions about the value of an
 * <code>Activity.Ok</code> state or the exception of an <code>Activity.Failed</code> state.
 *
 * <p>
 * The <code>ok</code> method will return the value of the <code>Activity.State</code> on which it is invoked if the
 * state is <code>Ok</code>, or throw <code>TestFailedException</code> if not.
 * The <code>failed</code> method will return the exception value of an <code>Activity.State</code> on which it is
 * invoked if the state is a <code>Activity.Failed</code>, or throw <code>TestFailedException</code> if not.
 * Finally, the <code>pending</code> method, which is largely included for completeness, will return <code>()</code> if
 * <code>Activity.State</code> on which it is invoked if the state is <code>Activity.Pending</code>, or throw
 * <code>TestFailedException</code> if not.
 * </p>
 *
 * <p>
 * This construct allows you to express in one statement that an <code>Activity.State</code> should be an
 * <code>Activity.Ok</code>, <code>Activity.Pending</code>, or an <code>Activity.Failed</code>, and that its value or
 * exception (if it is Ok or Failed), respectively,should meet some expectation. Here's an example:
 * </p>
 *
 * <pre class="stHighlight">
 * state1.ok should be &gt; 9
 * state2.failed should have message "/ by zero"
 * </pre>
 *
 * <p>
 * Or, using assertions instead of a matchers:
 * </p>
 *
 * <pre class="stHighlight">
 * assert(try1.ok &gt; 9)
 * assert(try2.failed.getMessage == "/ by zero")
 * </pre>
 *
 * <p>
 * This makes it much easier to expect that a given `Activity` is in a certain state than having to manually match
 * against the state.
 * </p>
 *
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

    def pending: Unit = state match {
      case Activity.Ok(t) =>
        throw new TestFailedException(s"expected Activity.Pending, found Activity.Ok($t)", 1)
      case Activity.Failed(e) =>
        throw new TestFailedException(s"expected Activity.Ok, found Activity.Failed($e)", e, 1)
      case Activity.Pending => ()
    }
  }

}
