package io.buoyant.test

import com.twitter.conversions.time._
import com.twitter.util.{Await, Duration, Future, Time, TimeoutException}
import org.scalatest.exceptions.TestFailedException

trait Awaits {

  def defaultWait: Duration = 250.millis
  def awaitStackDepth: Int = 4

  def await[T](t: Duration)(f: => Future[T]): T =
    try Await.result(f, t) catch {
      case cause: TimeoutException =>
        throw new TestFailedException(s"operation timed out after $t", cause, awaitStackDepth)
    }

  def await[T](ev: Events[T]): (T, Events[T]) = ev match {
    case Events.None() =>
      throw new TestFailedException(s"events underflow", awaitStackDepth)
    case ev =>
      await(ev.next())
  }

  def await[T](f: => Future[T]): T =
    await(defaultWait)(f)

}
