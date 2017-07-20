package com.twitter.finagle.buoyant.h2
import com.twitter.util.{Future, Try}

/**
 * Wraps an underlying [[Stream]] with an [[StreamProxy.onFrame onFrame]] function.
 * The `onFrame` function is called for each frame in the stream.
 * @param underlying the [[Stream]] wrapped by this proxy
 * @param onFrame function called for each [[Frame]] in the underlying [[Stream]]
 */
class StreamProxy(underlying: Stream, onFrame: Try[Frame] => Unit) extends Stream {
  override def isEmpty: Boolean = underlying.isEmpty

  override def read(): Future[Frame] = underlying.read().respond(onFrame)

  /**
   * Satisfied when an end-of-stream frame has been read from this
   * stream.
   *
   * If the stream is reset prematurely, onEnd fails with a [[Reset]].
   */
  override def onEnd: Future[Unit] = underlying.onEnd
  override def toString: String = s"StreamProxy($underlying, onFrame=$onFrame)"
}
