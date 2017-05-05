package com.twitter.finagle.buoyant.h2

import com.twitter.logging.Logger
import com.twitter.util._
import java.util.{ArrayDeque, Queue => JQueue}
import scala.collection.immutable.Queue

object AsyncQueue {
  private sealed trait State
  private case object Idle extends State
  private case object Offering extends State
  private case object Polling extends State
  private case class Excepting(exc: Throwable) extends State

  /** Indicates there is no max capacity */
  private val UnboundedCapacity = Int.MaxValue
}

/**
 * An asynchronous FIFO queue. In addition to providing [[offer]]
 * and [[poll]], the queue can be [[fail "failed"]], flushing current
 * pollers.
 *
 * @param maxPendingOffers optional limit on the number of pending `offers`.
 * The default is unbounded, but any other positive value can be used to limit
 * the max queue size. Note that `Int.MaxValue` is used to denote unbounded.
 *
 * @note thread safety is enforced via the intrinsic lock on `this` which must
 *       be acquired for any subclasses which want to serialize operations.
 */
class AsyncQueue[T](maxPendingOffers: Int) {
  private lazy val log = Logger.get(getClass.getName)

  import AsyncQueue._

  require(maxPendingOffers > 0)

  // synchronize all access to state, offers, and pollers
  private[this] var state: State = Idle

  // these aren't part of the state machine for performance
  private[this] val offers: JQueue[T] = new ArrayDeque[T]
  private[this] val pollers: JQueue[Promise[T]] = new ArrayDeque[Promise[T]]

  /**
   * An asynchronous, unbounded, FIFO queue. In addition to providing [[offer]]
   * and [[poll]], the queue can be [[fail "failed"]], flushing current
   * pollers.
   */
  def this() = this(AsyncQueue.UnboundedCapacity)

  /**
   * Returns the current number of pending elements.
   */
  final def size: Int = synchronized {
    offers.size
  }

  /**
   * Retrieves and removes the head of the queue, completing the
   * returned future when the element is available.
   */
  final def poll(): Future[T] = synchronized {
    state match {
      case Idle =>
        val p = new Promise[T]
        state = Polling
        pollers.offer(p)
        p

      case Polling =>
        val p = new Promise[T]
        pollers.offer(p)
        p

      case Offering =>
        val elem = offers.poll()
        if (offers.isEmpty)
          state = Idle
        Future.value(elem)

      case Excepting(t) if offers.isEmpty =>
        Future.exception(t)

      case Excepting(_) =>
        Future.value(offers.poll())
    }
  }

  /**
   * Insert the given element at the tail of the queue.
   *
   * @return `true` if the item was successfully added, `false` otherwise.
   */
  def offer(str: String, elem: T): Boolean = {
    val str1 = s"$str -- AsyncQueue.offer"
    var waiter: Promise[T] = null
    val result = synchronized {
      state match {
        case Idle =>
          log.debug("%s Idle", str1)
          state = Offering
          offers.offer(elem)
          true

        case Offering if offers.size >= maxPendingOffers =>
          log.debug("%s Offering maxPendingOffers", str1)
          false

        case Offering =>
          log.debug("%s Offering", str1)
          offers.offer(elem)
          true

        case Polling =>
          log.debug("%s Polling", str1)
          waiter = pollers.poll()
          if (pollers.isEmpty)
            state = Idle
          true

        case s@Excepting(_) =>
          log.debug("%s Excepting %s", str1, s)
          false // Drop.
      }
    }
    // we do this to avoid satisfaction while synchronized, which could lead to
    // deadlock if there are interleaved queue operations in the waiter closure.
    if (waiter != null)
      waiter.setValue(elem)
    result
  }

  /**
   * Drains any pending elements into a `Try[Queue]`.
   *
   * If the queue has been [[fail failed]] and is now empty,
   * a `Throw` of the exception used to fail will be returned.
   * Otherwise, return a `Return(Queue)` of the pending elements.
   */
  final def drain(): Try[Queue[T]] = synchronized {
    state match {
      case Offering =>
        state = Idle
        var q = Queue.empty[T]
        while (!offers.isEmpty) {
          q :+= offers.poll()
        }
        Return(q)
      case Excepting(e) if !offers.isEmpty =>
        var q = Queue.empty[T]
        while (!offers.isEmpty) {
          q :+= offers.poll()
        }
        Return(q)
      case Excepting(e) =>
        Throw(e)
      case _ =>
        Return(Queue.empty)
    }
  }

  /**
   * Fail the queue: current and subsequent pollers will be completed
   * with the given exception; any outstanding messages are discarded.
   */
  final def fail(str: String, exc: Throwable): Unit = fail(str, exc, discard = true)

  /**
   * Fail the queue. When `discard` is true, the queue contents is discarded
   * and all pollers are failed immediately. When this flag is false, subsequent
   * pollers are not failed until the queue becomes empty.
   *
   * No new elements are admitted to the queue after it has been failed.
   */
  def fail(str: String, exc: Throwable, discard: Boolean): Unit = {
    val str1 = s"$str -- AsyncQueue.fail"
    var q: Queue[Promise[T]] = null
    synchronized {
      state match {
        case Idle =>
          log.debug("%s Idle", str1)
          state = Excepting(exc)

        case Polling =>
          log.debug("%s Polling", str1)
          state = Excepting(exc)
          q = Queue.empty[Promise[T]]
          while (!pollers.isEmpty) {
            val waiter = pollers.poll()
            q :+= waiter
          }

        case Offering =>
          log.debug("%s Offering", str1)
          if (discard)
            offers.clear()
          state = Excepting(exc)

        case s@Excepting(_) =>
          log.debug("%s Excepting %s", str1, s)
        // Just take the first one.
      }
    }
    // we do this to avoid satisfaction while synchronized, which could lead to
    // lock contention if closures on the promise are slow or there are a lot of
    // them
    if (q != null) {
      q.foreach { p =>
        p.setException(exc)
      }
    }
  }

  override def toString = "AsyncQueue<%s>".format(synchronized(state))
}
