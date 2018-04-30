package com.twitter.finagle.buoyant.h2
import com.twitter.util.{Future, Try}
import scala.collection.mutable

abstract class StreamProxy(underlying: Stream) extends Stream {
  /**
   * @return true if the underlying stream is empty
   */
  override def isEmpty: Boolean = underlying.isEmpty
  /**
   * Satisfied when an end-of-stream frame has been read from this
   * stream.
   *
   * If the stream is reset prematurely, onEnd fails with a Reset.
   */
  override def onEnd: Future[Unit] = underlying.onEnd

  override def cancel(reset: Reset): Unit = underlying.cancel(reset)

  override def onCancel: Future[Reset] = underlying.onCancel
}
/**
 * Wraps an underlying Stream with an onFrame
 * function.
 * The `onFrame` function is called for each frame in the stream.
 *
 * @param underlying the Stream wrapped by this proxy.
 * @param onFrame function called for each Frame in the underlying Stream.
 */
// TODO: consider renaming `onFrame` to `foreach`?
class StreamOnFrame(underlying: Stream, onFrame: Try[Frame] => Unit)
  extends StreamProxy(underlying) {
  override def read(): Future[Frame] = underlying.read().respond(onFrame)
}

/**
 * Wraps an underlying Stream with a function `f` constructing a new
 * Stream backed by the original stream with `f` applied to each frame.
 *
 * @note This is essentially the same as the
 *       scala.collection.TraversableLike.flatMap() flatMap function on
 *       Scala collections, but with the limitation that the `flatMap`ped
 *       function may not change the type of the mapped element (since H2
 *       Streams always consist of Frames). To be properly monadic,
 *       we would have the `flatMap`ped function return a new Stream
 *       which would be spliced into this stream, but constructing streams
 *       is much more complex (you have to make an AsyncQueue and offer frames
 *       to it one by one...), so we cheat a little by having the
 *       `flatMap`ped function yield a Seq of frames instead.
 * @note that in order to avoid violating flow control, `f` must either
 *       take ownership over the frame and eventually release it, or return
 *       it in the returned sequence of frames.
 * @param underlying the Stream wrapped by this proxy.
 * @param f function called on each Frame in the underlying Stream.
 *
 */
class StreamFlatMap(underlying: Stream, f: Frame => Seq[Frame])
  extends StreamProxy(underlying) {
  private[this] var q = new mutable.Queue[Frame]()

  /**
   * @return true if the underlying Stream.isEmpty
   * @note that a StreamFlatMap proxy may continue to return Frames
   *       on calls to Stream.read() after this function returns
   *       true, if the underlying stream is empty but  there are frames
   *       remaining in the `StreamFlatMap` proxy's internal queue.
   */
  @inline override def isEmpty: Boolean = underlying.isEmpty

  /**
   * @note that if the StreamFlatMap proxy's internal queue of Frames
   *       is non-empty, a frame will be dequeued from it, rather than read
   *       from the underlying stream. if the queue is empty, `f` will be
   *       called on a frame read from the underlying stream before returning
   *       it, and  additional frames may be enqueued to be read before that
   *       frame.
   */
  override def read(): Future[Frame] = synchronized {
    if (q.nonEmpty) Future.value(q.dequeue())
    else underlying.read().map { frame =>
      q.enqueue(f(frame): _*)
      q.dequeue()
    }
  }
}
