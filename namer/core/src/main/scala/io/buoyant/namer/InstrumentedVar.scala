package io.buoyant.namer

import com.twitter.util._

/**
 * VarState represents a snapshot of the state of a Var.  This class is intended for serialization.
 */
case class VarState[T](
  running: Boolean,
  lastStartedAt: Option[String],
  lastStoppedAt: Option[String],
  lastUpdatedAt: Option[String],
  value: T
) {
  def map[U](f: T => U): VarState[U] = copy(value = f(value))
}

/**
 * InstrumentedVar holds a Var.async and also records metadata about it such as whether it is
 * running and the value of the most recent update.
 */
class InstrumentedVar[T](init: T, update: Updatable[T] => Closable) {

  private[this] val instrumentedUpdate: Updatable[T] => Closable = { up =>
    recordStart()
    val instrumentedUp = new Updatable[T] {
      override def update(t: T): Unit = {
        recordUpdate(t)
        up() = t
      }
    }
    val closable = update(instrumentedUp)
    Closable.all(closable, Closable.make { _ =>
      recordStop()
      Future.Unit
    })
  }

  @volatile private[this] var _running = false
  @volatile private[this] var _lastStartedAt: Option[Time] = None
  @volatile private[this] var _lastStoppedAt: Option[Time] = None
  @volatile private[this] var _value: T = init
  @volatile private[this] var _lastUpdatedAt: Option[Time] = None

  val underlying: Var[T] = Var.async(init)(instrumentedUpdate)

  def running: Boolean = _running
  def lastStartedAt: Option[Time] = _lastStartedAt
  def lastStoppedAt: Option[Time] = _lastStoppedAt
  def lastUpdatedAt: Option[Time] = _lastUpdatedAt
  def value: T = _value

  /**
   * Returns a snapshot of the current state of the Var
   */
  def stateSnapshot(): VarState[T] = VarState[T](
    running,
    lastStartedAt.map(_.toString),
    lastStoppedAt.map(_.toString),
    lastUpdatedAt.map(_.toString),
    value
  )

  protected def recordStart(): Unit = synchronized {
    _running = true
    _lastStartedAt = Some(Time.now)
  }

  protected def recordStop(): Unit = synchronized {
    _running = false
    _lastStoppedAt = Some(Time.now)
  }

  protected def recordUpdate(t: T): Unit = synchronized {
    _value = t
    _lastUpdatedAt = Some(Time.now)
  }
}

object InstrumentedVar {
  def apply[T](empty: T)(update: Updatable[T] => Closable) = new InstrumentedVar[T](empty, update)
}

/**
 * InstrumentedActivity holds an Activity backed by an InstrumentedVar.  Therefore, it has access to
 * metadata about the underlying Var.async such as whether it is running and the value of the most
 * recent update.
 */
class InstrumentedActivity[T](update: Updatable[Activity.State[T]] => Closable) {

  private[this] val run = new InstrumentedVar[Activity.State[T]](Activity.Pending, update)

  def underlying: Activity[T] = Activity(run.underlying)

  def running: Boolean = run.running
  def lastStartedAt: Option[Time] = run.lastStartedAt
  def lastStoppedAt: Option[Time] = run.lastStoppedAt
  def lastUpdatedAt: Option[Time] = run.lastUpdatedAt
  def value: Activity.State[T] = run.value

  /**
   * Returns a snapshot of the current state of the underlying Var
   */
  def stateSnapshot(): VarState[Option[T]] = VarState[Option[T]](
    running,
    lastStartedAt.map(_.toString),
    lastStoppedAt.map(_.toString),
    lastUpdatedAt.map(_.toString),
    value match {
      case Activity.Ok(t) => Some(t)
      case _ => None
    }
  )
}

object InstrumentedActivity {
  def apply[T](update: Updatable[Activity.State[T]] => Closable) = new InstrumentedActivity[T](update)
}
