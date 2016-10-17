package com.twitter.finagle.buoyant.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.io.Buf
import com.twitter.finagle.Failure
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Future, Promise, Stopwatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace

object AsyncQueueStreamer {
  private val log = com.twitter.logging.Logger.get(getClass.getName)

  /**
   * States of a data stream
   */
  private sealed trait State

  /** The stream is open and more data is expected */
  private object Open extends State

  /** All data has been read to the user, but there is a pending trailers frame to be returned. */
  private case class Draining(trailers: Frame.Trailers) extends State

  /** The stream has been closed with a cause; all subsequent reads will fail */
  private case class Closed(cause: Throwable) extends State

  private val closedException =
    new IllegalStateException("stream closed") with NoStackTrace

  private val closedState =
    Closed(closedException)

  private def expectedOpenException(state: State) =
    new IllegalStateException(s"expected Open state; found ${state}")

  private val illegalReadException =
    new IllegalStateException("No data or trailers available")
}

/**
 * An Http2 Data stream that serves values from a queue of `T`-typed
 * frames.
 *
 * Useful when reading a Message from a Transport. The transport can
 * just put data & trailer frames on the queue without processing.
 *
 * Data frames may be combined if there are at least `minAccumFrames`
 * pending in the queue. This allows the queue to be flushed more
 * quickly as it backs up. By default, frames are not accumulated.
 */
private[h2] abstract class AsyncQueueStreamer[-T](
  minAccumFrames: Int = Int.MaxValue,
  stats: StatsReceiver = NullStatsReceiver
) extends Stream.Reader with Stream.Writer[T] {

  import AsyncQueueStreamer._

  private[this] val recvq: AsyncQueue[T] = new AsyncQueue

  private[this] val accumMicros = stats.stat("accum_us")
  private[this] val readQlens = stats.stat("read_qlen")
  private[this] val readMicros = stats.stat("read_us")

  private[this] val state: AtomicReference[State] = new AtomicReference(Open)

  private[this] val endP = new Promise[Unit]
  override def onEnd: Future[Unit] = endP

  /** Fail the underlying queue and*/
  override def reset(exn: Throwable): Unit =
    if (state.compareAndSet(Open, Closed(exn))) {
      recvq.fail(exn, discard = true)
      endP.setException(exn)
    }

  override def write(t: T): Boolean =
    state.get match {
      case Open => recvq.offer(t)
      case _ => false
    }

  /**
   * Read a Data from the underlying queue.
   *
   * If there are at least `minAccumFrames` in the queue, data frames may be combined
   */
  override def read(): Future[Frame] = {
    val start = Stopwatch.start()
    val f = state.get match {
      case Open =>
        val sz = recvq.size
        readQlens.add(sz)
        if (sz < minAccumFrames) recvq.poll().map(toFrameAndUpdate)
        else Future.const(recvq.drain()).map(accumStreamAndUpdate)

      case Closed(cause) => Future.exception(cause)

      case s@Draining(trailers) =>
        if (state.compareAndSet(s, closedState)) {
          recvq.fail(closedException)
          endP.setDone()
          Future.value(trailers)
        } else Future.exception(closedException)
    }
    f.onSuccess(_ => readMicros.add(start().inMicroseconds))
    f
  }

  private[this] val toFrameAndUpdate: T => Frame = { t =>
    val f = toFrame(t)
    if (f.isEnd) {
      if (state.compareAndSet(Open, closedState)) endP.setDone()
      else throw expectedOpenException(state.get)
    }
    f
  }

  protected[this] def toFrame(t: T): Frame

  /**
   * Accumulate a queue of stream frames to a single frame.
   *
   * If a trailers frame exists
   */
  private val accumStreamAndUpdate: Queue[T] => Frame = { frames =>
    require(frames.nonEmpty)
    val start = Stopwatch.start()

    val accum = accumStream(frames)
    val next = accum.data match {
      case null =>
        accum.trailers match {
          case null => throw illegalReadException
          case trls => trls
        }
      case data =>
        accum.trailers match {
          case null => data
          case trls =>
            if (state.compareAndSet(Open, Draining(trls))) data
            else throw expectedOpenException(state.get)
        }
    }
    if (next.isEnd) endP.setDone()

    accumMicros.add(start().inMicroseconds)
    next
  }

  protected[this] class StreamAccum {
    var data: Frame.Data = null
    var trailers: Frame.Trailers = null
  }

  @inline
  protected[this] def mkStreamAccum(data: Frame.Data, trailers: Frame.Trailers): StreamAccum = {
    val sa = new StreamAccum
    sa.data = data
    sa.trailers = trailers
    sa
  }

  /** May return null items. */
  protected[this] def accumStream(frames: Queue[T]): StreamAccum
}
