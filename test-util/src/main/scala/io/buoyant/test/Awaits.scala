package io.buoyant.test

import com.twitter.conversions.time._
import com.twitter.util.{Await, Duration, Future, TimeoutException}
import org.scalatest.concurrent.Eventually
import org.scalatest.exceptions.TestFailedException
import org.scalatest.time.{Millis, Span}

trait Awaits extends Eventually {

  def defaultWait: Duration =
    sys.env.get("CI_TERRIBLENESS").map(Duration.parse(_)).getOrElse(2.seconds)

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(defaultWait.inMillis, Millis)))

  def awaitStackDepth: Int = 5

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
