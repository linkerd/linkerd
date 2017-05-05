package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._

/**
 * A Stream represents a stream of Data frames, optionally
 * followed by Trailers.
 *
 * A Stream is not prescriptive as to how data is produced. However,
 * flow control semantics are built into the Stream--consumers MUST
 * release each data frame after it has processed its contents.
 *
 * Consumers SHOULD call `read()` until it fails (i.e. when the
 * stream is fully closed).
 *
 * If a consumer cancels a `read()` Future, the stream is reset.
 */
trait Stream {
  override def toString = s"Stream(isEmpty=$isEmpty, onEnd=${onEnd.poll})"

  def isEmpty: Boolean
  final def nonEmpty: Boolean = !isEmpty

  def read(str: String): Future[Frame]

  /**
   * Satisfied when an end-of-stream frame has been read from this
   * stream.
   *
   * If the stream is reset prematurely, onEnd fails with a [[Reset]].
   */
  def onEnd: Future[Unit]
}

/**
 * A Stream of Frames
 */
object Stream {
  private lazy val log = Logger.get(getClass.getName)

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
     * (i.e. onto an underlying transport). The returned future is not
     * satisfied until the frame is written and released.
     */
    def write(str: String, frame: Frame): Future[Unit]

    def reset(str: String, err: Reset): Unit
    def close(str: String): Unit
  }

  private trait AsyncQueueReader extends Stream {
    protected[this] val frameQ: AsyncQueue[Frame]

    override def isEmpty = false

    private[this] val endP = new Promise[Unit]
    override def onEnd: Future[Unit] = endP

    private[this] val endOnReleaseIfEnd: Try[Frame] => Unit = {
      case Return(f) => if (f.isEnd) endP.become(f.onRelease)
      case Throw(e) => endP.updateIfEmpty(Throw(e)); ()
    }

    override def read(str: String): Future[Frame] = {
      val str1 = s"$str -- AsyncQueueReader.read"
      log.debug(str1)
      val f = frameQ.poll()
      f.respond(endOnReleaseIfEnd)
      failOnInterrupt(str1, f, frameQ)
    }
  }

  private[this] def failOnInterrupt[T, Q](str: String, f: Future[T], q: AsyncQueue[Q]): Future[T] = {
    val str1 = s"$str -- failOnInterrupt"
    val p = new Promise[T]
    p.setInterruptHandler {
      case e =>
        log.debug("%s %s %s %d", str1, this, q.toString, q.size)
        q.fail(str1, e, discard = true)
        f.raise(e)
    }
    f.proxyTo(p)
    p
  }

  private class AsyncQueueReaderWriter extends AsyncQueueReader with Writer {
    override protected[this] val frameQ = new AsyncQueue[Frame]

    override def write(str: String, f: Frame): Future[Unit] = {
      val str1 = s"$str -- AsyncQueueReaderWriter.write"
      if (frameQ.offer(str1, f)) {
        log.debug("%s offer=true", str1)
        failOnInterrupt(str1, f.onRelease, frameQ)
      } else {
        log.debug("%s offer=false", str1)
        Future.exception(Reset.Closed)
      }
    }

    override def reset(str: String, err: Reset): Unit = {
      val str1 = s"$str -- AsyncQueueReaderWriter.reset"
      log.debug("%s %s %s %d", str1, this, frameQ.toString, frameQ.size)
      frameQ.fail(str1, err, discard = true)
    }
    override def close(str: String): Unit = {
      val str1 = s"$str -- AsyncQueueReaderWriter.close"
      log.debug("%s %s %s %d", str1, this, frameQ.toString, frameQ.size)
      frameQ.fail(str, Reset.NoError, discard = false)
    }
  }

  def apply(q: AsyncQueue[Frame]): Stream =
    new AsyncQueueReader { override protected[this] val frameQ = q }

  def apply(): Stream with Writer =
    new AsyncQueueReaderWriter

  def const(str: String, f: Frame): Stream = {
    val q = new AsyncQueue[Frame]
    q.offer(str, f)
    apply(q)
  }

  def const(str: String, buf: Buf): Stream =
    const(str, Frame.Data.eos(buf))

  def const(str: String, s: String): Stream =
    const(str, Buf.Utf8(s))

  def empty(q: AsyncQueue[Frame]): Stream =
    new AsyncQueueReader {
      override protected[this] val frameQ = q
      override def isEmpty = true
    }

  def empty(): Stream with Writer =
    new Stream with Writer {
      private[this] val frameQ = new AsyncQueue[Frame](1)
      override def isEmpty = true
      override def onEnd = Future.Unit
      override def read(str: String): Future[Frame] = {
        val str1 = s"$str -- Stream.empty.read"
        failOnInterrupt(str1, frameQ.poll(), frameQ)
      }
      override def write(str: String, f: Frame): Future[Unit] = {
        val str1 = s"$str -- Stream.empty.write"
        frameQ.fail(str1, Reset.Closed, discard = true)
        Future.exception(Reset.Closed)
      }
      override def reset(str: String, err: Reset): Unit = {
        val str1 = s"$str -- Stream.empty.reset"
        frameQ.fail(str1, err, discard = true)
      }
      override def close(str: String): Unit = {
        val str1 = s"$str -- Stream.empty.close"
        frameQ.fail(str1, Reset.NoError, discard = false)
      }
    }

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
    override def toString = s"Frame.Data(length=${buf.length}, isEnd=$isEnd)"
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

  object Trailers {

    private class Impl(orig: Seq[(String, String)])
      extends Headers.Impl(orig) with Trailers {

      private[this] val promise = new Promise[Unit]
      def release(): Future[Unit] = {
        promise.updateIfEmpty(Return.Unit)
        Future.Unit
      }
      def onRelease: Future[Unit] = promise
    }

    def apply(pairs: Seq[(String, String)]): Trailers =
      new Impl(pairs)

    def apply(hd: (String, String), tl: (String, String)*): Trailers =
      apply(hd +: tl)

    def apply(): Trailers =
      apply(Nil)
  }

}
