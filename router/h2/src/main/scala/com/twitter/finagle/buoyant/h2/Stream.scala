package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Failure
import com.twitter.util.{Future, Promise}

/**
 * A Stream represents a stream of Data frames, optionally
 * followed by Trailers.
 *
 * A Stream is not prescriptive as to how data is produced. However,
 * flow control semantics are built into the Stream--consumers MUST
 * release each data frame after it has processed its contents.
 */
sealed trait Stream {
  def isEmpty: Boolean
  final def nonEmpty: Boolean = !isEmpty
  def onEnd: Future[Unit]
}

/**
 * A Stream of Frames
 */
object Stream {

  /**
   * An empty stream. Useful, for instance, when a Message consists of
   * only a headers frame with END_STREAM set.
   */
  object Nil extends Stream {
    override def toString = "Stream.Nil"
    override def isEmpty = true
    override def onEnd = Future.Unit
  }

  trait Reader extends Stream {
    override def toString = s"Stream.Reader()"
    final override def isEmpty = false
    def onEnd: Future[Unit]
    def read(): Future[Frame]
    def reset(e: Error.StreamError): Future[Unit]
  }

  // TODO Create a dedicated Static stream type that indicates the
  // entire message is buffered (i.e. so that retries may be
  // performed).  This would require some form of buffering, ideally
  // in the dispatcher and server stream

  /**
   * In order to create a stream, we need a mechanism to write to it.
   */
  trait Writer {

    /**
     * Write an object to a Stream so that it may be read as a Frame
     * (i.e. onto an underlying transport).
     */
    def write(frame: Frame): Future[Unit]
  }

  private trait AsyncQueueReader extends Reader {
    protected[this] val frameQ: AsyncQueue[Frame]

    private[this] val endP = new Promise[Unit]
    private[this] val endOnReleaseIfEnd: Frame => Unit = { f =>
      if (f.isEnd) endP.become(f.onRelease)
    }

    override def onEnd: Future[Unit] = endP
    override def read(): Future[Frame] = {
      val f = frameQ.poll()
      f.onSuccess(endOnReleaseIfEnd)
      f
    }

    override def reset(e: Error.StreamError): Future[Unit] = {
      frameQ.fail(Error.ResetException(e), discard = true)
      Future.Unit
    }
  }

  private class AsyncQueueReaderWriter extends AsyncQueueReader with Writer {
    override protected[this] val frameQ = new AsyncQueue[Frame]

    override def write(f: Frame): Future[Unit] = {
      if (frameQ.offer(f)) f.onRelease
      else Future.exception(Failure("write failed").flagged(Failure.Rejected))
    }
  }

  def apply(q: AsyncQueue[Frame]): Reader =
    new AsyncQueueReader { override protected[this] val frameQ = q }

  def apply(): Reader with Writer =
    new AsyncQueueReaderWriter

  def const(buf: Buf): Reader = {
    val q = new AsyncQueue[Frame]
    q.offer(Frame.Data.eos(buf))
    apply(q)
  }

  def const(s: String): Reader =
    const(Buf.Utf8(s))
}

/**
 * A single item in a Stream.
 */
sealed trait Frame {

  /**
   * When `isEnd` is true, no further events will be returned on the
   * Stream.
   */
  def isEnd: Boolean

  def onRelease: Future[Unit]
  def release(): Future[Unit]
}

object Frame {
  /**
   * A frame containing aribtrary data.
   *
   * `release()` MUST be called so that the producer may manage flow control.
   */
  trait Data extends Frame {
    override def toString = s"Frame.Data(length=${buf.length}, eos=$isEnd)"
    def buf: Buf
  }

  object Data {

    def apply(buf0: Buf, eos: Boolean, release0: () => Future[Unit]): Data = new Data {
      def buf = buf0
      private[this] val releaseP = new Promise[Unit]
      def onRelease = releaseP
      def release() = {
        val f = release0()
        releaseP.become(f)
        f
      }
      def isEnd = eos
    }

    def apply(buf: Buf, eos: Boolean): Data =
      apply(buf, eos, () => Future.Unit)

    def apply(s: String, eos: Boolean, release: () => Future[Unit]): Data =
      apply(Buf.Utf8(s), eos, release)

    def apply(s: String, eos: Boolean): Data =
      apply(Buf.Utf8(s), eos)

    def eos(buf: Buf): Data = apply(buf, true)
    def eos(s: String): Data = apply(s, true)
  }

  /** A terminal Frame including headers. */
  trait Trailers extends Frame with Headers { headers =>
    override def toString = s"Frame.Trailers(${headers.toSeq})"
    final override def isEnd = true
  }

  /** A terminal Frame ending the stream abruptly. */
  case class Reset(error: Error.StreamError) extends Frame {
    override def toString = s"Frame.Reset(${error})"
    final override def isEnd = true

    private[this] val releaseP = new Promise[Unit]
    override def onRelease: Future[Unit] = releaseP
    override def release() = {
      releaseP.setDone()
      Future.Unit
    }
  }
}
