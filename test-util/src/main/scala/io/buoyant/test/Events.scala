package io.buoyant.test

import com.twitter.conversions.DurationOps._
import com.twitter.util._
import org.scalatest._
import scala.collection.JavaConverters._

object Closed extends Throwable

/**
 * Represents a slice of an Event as a sequence of futures that may be processed in order.
 *
 * Just a helper (mostly for tests).
 */
class Events[T] private (pending: List[Future[T]], closable: Closable) extends Closable {

  def size = pending.size
  def length = pending.length
  def isEmpty = pending.isEmpty
  def nonEmpty = pending.nonEmpty

  def map[U](f: T => U): Events[U] =
    new Events(pending.map(_.map(f)), this)

  def flatMap[U](f: T => Future[U]): Events[U] =
    new Events(pending.map(_.flatMap(f)), this)

  def next(): Future[(T, Events[T])] = pending match {
    case hd :: tail =>
      val next = new Events(tail, this)
      hd map ((_, next))

    case _ =>
      Future.exception(Events.Underflow)
  }

  def close(deadline: Time) =
    closable.close(deadline) ensure pending.foreach(_.raise(Closed))
}

object Events {
  private[this] def thread = Thread.currentThread.getName

  /** Lazily take `n` values from `changes`. */
  def take[T](n: Int, changes: Event[T]): Events[T] = {
    var promises: List[Promise[T]] = (0 until n).toList map (_ => new Promise[T])
    val mu = new {}
    new Events(promises, changes respond { v =>
      mu.synchronized {
        promises match {
          case promise :: tail =>
            promise.setValue(v)
            promises = tail
          case _ =>
        }
      }
    })
  }

  /**
   * Take the first `n` states of the provided activity.
   *
   * If `dedupe` is set to true, consecutive identical (e.g. Pending) states will
   * collapsed to a single event.
   */
  def takeStates[T](
    n: Int,
    activity: Activity[T],
    dedupe: Boolean = true
  ): Events[Activity.State[T]] =
    take(n, ifDedupe(dedupe, activity))

  def takeValues[T](n: Int, activity: Activity[T], dedupe: Boolean = true): Events[Try[T]] =
    take(n, ifDedupe(dedupe, activity) collect {
      case Activity.Failed(e) => Throw(e)
      case Activity.Ok(v) => Return(v)
    })

  private[this] def ifDedupe[T](cond: Boolean, activity: Activity[T]): Event[Activity.State[T]] =
    if (cond) dedupe(activity.run.changes)
    else activity.run.changes

  object None {
    def unapply(any: Any): Boolean = any match {
      case events: Events[_] => events.isEmpty
      case _ => false
    }
  }

  object Underflow extends Exception("event underflow")

  /**
   * Remove consecutive identical values from an Event.
   */
  def dedupe[T](ev: Event[T]): Event[T] = new Event[T] {
    def register(w: Witness[T]) = {
      var prior: Option[T] = scala.None
      val mu = new {}
      ev respond { v =>
        mu.synchronized {
          prior match {
            case Some(u) if u.equals(v) =>
            case _ =>
              prior = Some(v)
              w.notify(v)
          }
        }
      }
    }
  }

}
