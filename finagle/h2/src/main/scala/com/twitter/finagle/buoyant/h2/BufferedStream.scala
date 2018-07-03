package com.twitter.finagle.buoyant.h2

import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2.BufferedStream.{RefCountedDataFrame, RefCountedFrame, RefCountedTrailersFrame, State}
import com.twitter.finagle.buoyant.h2.Stream.AsyncQueueReader
import com.twitter.finagle.util.AsyncLatch
import com.twitter.util._
import io.netty.buffer.ByteBuf
import scala.collection.mutable
import scala.util.control.NoStackTrace

/**
 * A BufferedStream reads Frames from an underlying Stream and stores them in a buffer.
 * BufferedStream itself is not a Stream but child Streams can be created from it by calling
 * fork().  Child Streams will first be offered all of the buffered Frames and then all Frames
 * read from the underlying Stream will also offered to the child Streams.
 *
 * When the buffer becomes full or when discardBuffer() is called, the buffer will be discarded
 * and no new child Streams may be created.  However, child Streams that had previously been
 * created will continue to receive Frames as they are read from the underlying Stream.
 *
 * The Frames that are stored in the buffer and offered to the child Streams are refcounted and
 * the underlying Frame will only be released once each child Stream releases the Frame and the
 * buffer itself releases the Frame.  Threrefore you should always call discardBuffer on a
 * BufferedStream before it leaves scope.
 */
class BufferedStream(underlying: Stream, bufferCapacity: Long = 16383) { bufferedStream =>

  // Mutable state.  All mutations of state must be explicitly synchronized
  private[this] val buffer = mutable.MutableList[RefCountedFrame]()
  private[this] var _bufferSize = 0L
  private[this] val forks = mutable.MutableList[AsyncQueue[Frame]]()
  private[this] var state: State = State.Buffering
  private[this] val _onEnd = new Promise[Unit]

  def bufferSize: Long = _bufferSize

  def onEnd: Future[Unit] = if (underlying.isEmpty)
    Future.Unit
  else
    _onEnd

  /**
   * Attempt to create a child Stream.  If the buffer has not yet been discarded, returns a
   * Stream and offers the Frames in the buffer to that Stream.  All further Frames that are read
   * from the underlying Stream will offered to that Stream as well.  If the buffer has been
   * discarded, it is impossible to create a child Stream since the beginning of the Stream is
   * lost.  In this case, return a Throw.
   */
  def fork(): Try[Stream] = {
    state match {
      case State.Buffering => synchronized {
        val q = new AsyncQueue[Frame]()

        for (f <- buffer) {
          f.open()
          // Any instance of RefCountedFrame must also be an instance of Frame so this cast should
          // be safe.
          q.offer(f.asInstanceOf[Frame])
        }
        forks += q
        val stream = if (underlying.isEmpty) {
          Stream.empty()
        } else {
          new AsyncQueueReader { child =>
            override protected[this] val frameQ = q
            override def read(): Future[Frame] = bufferedStream.synchronized {
              // If the queue is empty, pull a Frame from the underlying Stream (which will be
              // offered to all child Streams) before polling the queue.
              if (q.size == 0) {
                // We must be careful to only have one pull of the underlying Stream at a time,
                // otherwise we could build up an unbounded list of pollers.  If N forks all call
                // read, a single pull of the underlying Stream is sufficient to satisfy all of
                // the forks because the pulled Frame is fanned out.
                pullState match {
                  case Idle =>
                    val f = bufferedStream.pull()
                    pullState = Pulling(f)
                    f.ensure { pullState = Idle }
                    super.read()
                  case Pulling(f) =>
                    // Pull is in progress.  Try again once the pull is complete.
                    f.before(read())
                }
              } else {
                super.read()
              }
            }
          }
        }
        stream.onCancel.onSuccess { rst =>
          underlying.cancel(rst)
        }
        Return(stream)
      }
      case e: Exception => Throw(e)
    }
  }

  private[this] sealed trait PullState
  private[this] case object Idle extends PullState
  private[this] case class Pulling(f: Future[Unit]) extends PullState

  @volatile private[this] var pullState: PullState = Idle

  /**
   * Read a Frame from the underlying Stream and write it into the buffer and offer it to the child
   * Streams.
   */
  private[this] def pull(): Future[Unit] = {
    if (underlying.isEmpty) {
      Future.Unit
    } else {
      underlying.read().transform {
        case Return(f: Frame.Data) => synchronized {
          val refCounted = new RefCountedDataFrame(f)
          handleFrame(refCounted, f.buf.readableBytes)
          if (f.isEnd) _onEnd.setDone()
          Future.Unit
        }
        case Return(f: Frame.Trailers) => synchronized {
          val refCounted = new RefCountedTrailersFrame(f)
          handleFrame(refCounted, 0)
          if (f.isEnd) _onEnd.setDone()
          Future.Unit
        }
        case Throw(e) => synchronized {
          // If we fail to read from the underlying stream, discard the buffer and fail the children
          // (Throw the babies out with the buffer)
          discardBuffer()
          for (fork <- forks) fork.fail(e, discard = false)
          state = State.Failed(e)
          Future.exception(e)
        }
      }
    }
  }

  private[this] val bufferDiscardedP = new Promise[Unit]
  val onBufferDiscarded: Future[Unit] = bufferDiscardedP

  /**
   * Clear the buffer and release all the Frames.  Note that the Frames are reference counted so
   * each underlying Frame will not be released until all child Streams have released the Frame as
   * well.  After this is called, it will no longer be possible to create child Streams.
   */
  def discardBuffer(): Unit = synchronized {
    if (state == State.Buffering) {
      state = State.BufferDiscarded
      for (bufferFrame <- buffer) bufferFrame.release()
      buffer.clear()
      val _ = bufferDiscardedP.setDone()
    }
  }

  /**
   * Offer the Frame to all child Streams and add it to the buffer if there's enough room.
   */
  private[this] def handleFrame(frame: RefCountedFrame, size: Int): Unit = {
    // Offer the frame to all child Streams
    for (fork <- forks) {
      frame.open()
      fork.offer(frame.asInstanceOf[Frame])
    }
    if (state == State.Buffering) {
      // Attempt to add the Frame to the buffer
      if (_bufferSize + size <= bufferCapacity) {
        buffer += frame
        _bufferSize += size
      } else {
        // There is not enough room in the buffer for this Frame.  Since it is now impossible
        // for a newly created child Stream to catch up, discard the buffer.
        frame.release()
        discardBuffer()
      }
    } else {
      // We're not storing this Frame in the buffer so we need to release our reference to it
      val _ = frame.release()
    }
  }
}

object BufferedStream {

  sealed trait State
  object State {
    // The buffer is not full, child Streams may still be created.
    case object Buffering extends State
    // The buffer has been discarded, no new child Streams may be created.
    case object BufferDiscarded extends Exception("BufferedStream capacity exceeded") with State with NoStackTrace
    // Reading from the underlying Stream has failed.
    case class Failed(e: Throwable) extends Exception("BufferedStream failed", e) with State with NoStackTrace
  }

  trait RefCountedFrame { self: Frame =>

    val underlying: Frame

    val latch = new AsyncLatch(1)
    val releasedP = new Promise[Unit]
    val released = new AtomicBoolean(false)

    latch.await {
      if (released.compareAndSet(false, true)) {
        releasedP.become(underlying.release())
      }
    }

    def open(): Unit = { latch.incr(); () }
    override def onRelease: Future[Unit] = releasedP
    override def release(): Future[Unit] = {
      latch.decr()
      Future.Unit
    }
  }

  class RefCountedDataFrame(val underlying: Frame.Data) extends Frame.Data with RefCountedFrame {
    override def isEnd: Boolean = underlying.isEnd
    override def buf: ByteBuf = underlying.buf
  }

  class RefCountedTrailersFrame(val underlying: Frame.Trailers) extends Frame.Trailers with RefCountedFrame {
    override def toSeq: Seq[(String, String)] = underlying.toSeq
    override def contains(k: String): Boolean = underlying.contains(k)
    override def get(k: String): Option[String] = underlying.get(k)
    override def getAll(k: String): Seq[String] = underlying.getAll(k)
    override def add(k: String, v: String): Unit = underlying.add(k, v)
    override def set(k: String, v: String): Unit = underlying.set(k, v)
    override def remove(key: String): Seq[String] = underlying.remove(key)
    /** Create a deep copy. */
    override def dup(): Headers = underlying.dup()
  }

}
