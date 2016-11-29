package io.buoyant.grpc.runtime

import com.twitter.util.{Activity, Event, Future, Promise, Return, Throw, Try, Var}
import java.util.concurrent.atomic.AtomicReference
<<<<<<< HEAD
=======
import scala.annotation.tailrec
>>>>>>> 8f4b678b... Introduce io.buoyant.grpc.runtime.EventStream

object EventStream {

  def apply[T](event: Event[Ev[T]]): Stream[T] =
    new EventStream(event)

  def apply[T](var0: Var[Ev[T]]): Stream[T] =
    apply(var0.changes)

  def apply[T](act: Activity[T]): Stream[T] =
    apply(act.values.map {
      case Return(t) => Val(t)
      case Throw(e) => End(Throw(e))
    })

  /** An T-typed event value */
  sealed trait Ev[+T]

  /** A non-terminal event, containing a value */
  case class Val[T](value: T) extends Ev[T]

  /** A terminal event, containing either a value or an error */
  case class End[T](value: Try[T]) extends Ev[T]

  private sealed trait State[+T]
  private object State {
<<<<<<< HEAD
    object Empty extends State[Nothing]
    case class Waiting[T](next: Promise[Unit]) extends State[T]
    case class Ready[T](value: Ev[T]) extends State[T]
=======
    /** There are no unconsumed values and no waiting receivers. */
    object Empty extends State[Nothing]

    /** There are no unconsumed values and there is a waiting receiver. */
    case class Recving[T](onUpdate: Promise[Unit]) extends State[T]

    /** An unconsumed value is ready. */
    case class Updated[T](value: Ev[T]) extends State[T]

    /** The event is no longer being observed and no further values will be produced. */
>>>>>>> 8f4b678b... Introduce io.buoyant.grpc.runtime.EventStream
    object Closed extends State[Nothing]
  }
}

/**
<<<<<<< HEAD
 * Convert an Event to a gRPC Stream.
=======
 * A gRPC stream for com.twitter.util.Event types.
>>>>>>> 8f4b678b... Introduce io.buoyant.grpc.runtime.EventStream
 *
 * Because this is designed to be used in the context of an
 * Activity/Var, the stream may NOT reflect all event states.
 * Intermediate state transitions may be dropped in favor of buffering
 * only the last-observed state.
 *
 * Event values are wrapped by EventStream.Ev to indicate whether a
 * value is terminal.
 *
 * TODO streams are not properly failed on event failure.
 *
 * TODO when a stream fails (from the remote), we should eagerly
 * stop observing the event.
 */
class EventStream[+T](events: Event[EventStream.Ev[T]]) extends Stream[T] {
  import EventStream._

  // We only buffer at most one event state.  Event states are not
  // written directly from the Event, since this provide no mechanism
  // for back-pressure. Updates to this state are performed without
  // locking.
  private[this] val stateRef: AtomicReference[State[T]] =
    new AtomicReference(State.Empty)

  // Updates from the Event move the state to Updated if the Stream is
  // not otherwise closed/completed.
  private[this] val updater = {
<<<<<<< HEAD
    @scala.annotation.tailrec
=======
    @tailrec
>>>>>>> 8f4b678b... Introduce io.buoyant.grpc.runtime.EventStream
    def updateState(value: Ev[T]): Unit = stateRef.get match {
      case State.Closed | State.Updated(End(_)) =>

      case s@(State.Empty | State.Updated(Val(_))) =>
        stateRef.compareAndSet(s, State.Updated(value)) match {
          case true =>
          case false => updateState(value)
        }

      case s@State.Recving(promise) =>
        // A recver is waiting for a value, so update before notifying
        // the recver of the update.
        stateRef.compareAndSet(s, State.Updated(value)) match {
          case true =>
            promise.updateIfEmpty(Return.Unit); ()
          case false => updateState(value)
        }
    }

    events.respond(updateState(_))
  }

  /**
   * Consume the most recent event value or wait for the next event
   * value.
   */
<<<<<<< HEAD
  override def recv(): Future[Stream.Releasable[T]] = stateRef.get match {
    // basically tailrec, except futurey.

    case State.Closed =>
      Future.exception(Stream.Closed)

=======
  // basically tailrec, except futurey.
  override def recv(): Future[Stream.Releasable[T]] = stateRef.get match {
>>>>>>> 8f4b678b... Introduce io.buoyant.grpc.runtime.EventStream
    case s@State.Empty =>
      val p = new Promise[Unit]
      stateRef.compareAndSet(s, State.Recving(p)) match {
        case true => p.before(recv())
        case false => recv()
      }

    case State.Recving(p) =>
      // Shouldn't actually reach this, but for the sake of completeness...
      p.before(recv())

    case s@State.Updated(Val(v)) =>
      stateRef.compareAndSet(s, State.Empty) match {
        case true => Future.value(Stream.Releasable(v))
        case false => recv()
      }

    case s@State.Updated(End(t)) =>
      stateRef.compareAndSet(s, State.Closed) match {
<<<<<<< HEAD
        case true =>
          updater.close()
            .before(Future.const(t.map(toReleasable)))
        case false => recv()
      }
  }

  private[this] def toReleasable: T => Stream.Releasable[T] =
=======
        case true => updater.close().before(Future.const(t.map(toReleasable)))
        case false => recv()
      }

    case State.Closed =>
      Future.exception(Stream.Closed)
  }

  private[this] val toReleasable: T => Stream.Releasable[T] =
>>>>>>> 8f4b678b... Introduce io.buoyant.grpc.runtime.EventStream
    Stream.Releasable(_)
}
