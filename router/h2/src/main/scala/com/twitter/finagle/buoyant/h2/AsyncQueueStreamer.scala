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
  // private case class Draining(trailers: Frame.Trailers) extends State

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

  private val writeFailure: Future[Unit] =
    Future.exception(Failure("write failed"))
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
private[h2] class AsyncQueueStreamer
  extends Stream.Reader
  with Stream.Writer {

  import AsyncQueueStreamer._

  private[this] val recvq: AsyncQueue[Frame] = new AsyncQueue
  private[this] val state: AtomicReference[State] = new AtomicReference(Open)

  private[this] val endP = new Promise[Unit]
  override def onEnd: Future[Unit] = endP

  override def write(f: Frame): Future[Unit] =
    state.get match {
      case Closed(exn) => Future.exception(exn)
      case Open =>
        // If writing an EOS, note that the stream is closed (so that
        // further writes fail).
        if (f.isEnd && !state.compareAndSet(Open, closedState)) writeFailure
        else if (recvq.offer(f)) f.onRelease
        else writeFailure
    }

  /**
   * Read a Data from the underlying queue.
   *
   * If there are at least `minAccumFrames` in the queue, data frames may be combined
   */
  override def read(): Future[Frame] = {
    val f = recvq.poll()
    f.onSuccess { f =>
      if (f.isEnd) f.onRelease.proxyTo(endP)
    }
    f
  }
}
