package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.util.{Future, Promise}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.Queue
import scala.collection.mutable

/**
 * A generic HTTP2 message.
 *
 * The message itself includes the Headers that initiated the message
 * (comprising all HEADERS frames sent to initiate the message).
 *
 * Furthermore, messages have an (optional) Stream of data and trailer
 * frames.
 *
 * These types are only intended to satisfy session layer concerned
 * and are not intended to provide a rish programming interface.
 * However, it is hoped that other types may be built upon / adapated
 * to satisfy a broader set of use cases.
 */
sealed trait Message {
  def headers: Headers
  def data: Stream

  /** Create a deep copy of the Message with a new copy of headers (but the same stream). */
  def dup(): Message
}

trait Headers {
  def toSeq: Seq[(String, String)]
  def contains(k: String): Boolean
  def get(k: String): Seq[String]
  def add(k: String, v: String): Unit
  def set(k: String, v: String): Unit
  def remove(key: String): Seq[String]

  /** Create a deep copy. */
  def dup(): Headers
}

object Headers {
  val Authority = ":authority"
  val Method = ":method"
  val Path = ":path"
  val Scheme = ":scheme"
  val Status = ":status"

  def apply(pairs: Seq[(String, String)]): Headers =
    new Impl(pairs)

  def apply(hd: (String, String), tl: (String, String)*): Headers =
    apply(hd +: tl)

  /**
   * Typically, headers wrap underlying netty types; but since we
   * don't want to bind user code to any netty APIs, we provide
   * another header implementation that can be translated by
   * transports.
   */
  private class Impl(orig: Seq[(String, String)]) extends Headers {
    private[this] var current: Seq[(String, String)] = orig
    def toSeq = synchronized(current)
    override def toString = s"""Headers(${toSeq.mkString(", ")})"""
    def contains(key: String) = toSeq.exists { case (k, _) => key == k }
    def get(key: String) = toSeq.collect { case (k, v) if key == k => v }
    def add(key: String, value: String) = synchronized {
      current = current :+ (key -> value)
    }
    def set(key: String, v: String) = synchronized {
      remove(key)
      current = current :+ (key -> v)
    }
    def remove(key: String) = synchronized {
      val isMatch: ((String, String)) => Boolean = { case (k, _) => key == k }
      val (removed, updated) = current.partition(isMatch)
      removed.map(toVal)
    }
    private[this] val toVal: ((String, String)) => String = { case (_, v) => v }
    def dup() = new Impl(synchronized(current))
  }
}

/**
 * Request messages include helpers for accessing pseudo-headers.
 */
trait Request extends Message {
  def scheme: String
  def method: Method
  def authority: String
  def path: String
  override def dup(): Request
}

object Request {
  def apply(
    scheme: String,
    method: Method,
    authority: String,
    path: String,
    data: Stream
  ): Request = Impl(
    Headers(
      Headers.Scheme -> scheme,
      Headers.Method -> method.toString,
      Headers.Authority -> authority,
      Headers.Path -> path
    ),
    data
  )

  def apply(headers: Headers, data: Stream): Request =
    Impl(headers, data)

  private case class Impl(headers: Headers, data: Stream) extends Request {
    override def toString = s"Request($scheme, $method, $authority, $path)"
    override def dup() = copy(headers = headers.dup())
    override def scheme = headers.get(Headers.Scheme).headOption.getOrElse("")
    override def method = headers.get(Headers.Method).headOption match {
      case Some(name) => Method(name)
      case None => throw new IllegalArgumentException("missing :method header")
    }
    override def authority = headers.get(Headers.Authority).headOption.getOrElse("")
    override def path = headers.get(Headers.Path).headOption.getOrElse("/")
  }
}

trait Response extends Message {
  def status: Status
  override def dup(): Response
}

object Response {
  def apply(status: Status, data: Stream): Response =
    Impl(Headers(Headers.Status -> status.code.toString), data)

  def apply(headers: Headers, data: Stream): Response =
    Impl(headers, data)

  private case class Impl(
    headers: Headers,
    data: Stream
  ) extends Response {
    override def toString = s"Response($status)"
    override def dup() = copy(headers = headers.dup())
    override def status = headers.get(Headers.Status).headOption match {
      case Some(code) => Status.fromCode(code.toInt)
      case None => throw new IllegalArgumentException(s"missing ${Headers.Status} header")
    }
  }
}

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
    override def toString = s"Stream.Readable"
    final override def isEmpty = false
    def reset(cause: Throwable): Unit
    def onEnd: Future[Unit]
    def read(): Future[Frame]
  }

  // TODO have a dedicated Static stream type that indicates the
  // entire message is buffered (i.e. so that retries may be
  // performed).  This would require some form of buffering, ideally
  // in the dispatcher and server stream

  /**
   * Does not support upstream flow control.
   */
  def const(buf: Buf): Reader =
    new Const(buf)

  private[this] class Const(buf: Buf) extends Reader {
    private[this] val endP = new Promise[Unit]
    private[this] val complete = new AtomicBoolean(false)
    override def onEnd: Future[Unit] = endP
    override def read(): Future[Frame] =
      if (complete.getAndSet(true) == false) {
        endP.setDone()
        Future.value(Frame.Data.eos(buf))
      } else Future.never
    override def reset(cause: Throwable): Unit = {
      complete.set(true)
      endP.raise(cause)
    }
  }

  /**
   * In order to create a stream, we need a mechanism to write to it.
   */
  trait Writer[In] {

    /**
     * Write an object to a Stream so that it may be read as a Frame
     * (i.e. onto an underlying transport).
     *
     * Returns `false` if the input could not be accepted.
     */
    def write(in: In): Boolean
  }

  def apply(minAccumFrames: Int = Int.MaxValue): Reader with Writer[Frame] =
    new FrameStream(minAccumFrames)

  /**
   * A default implementation of an Http2 stream that is backed by an
   * AsyncQueue of Frames.
   */
  private class FrameStream(minAccumFrames: Int)
    extends AsyncQueueStreamer[Frame](minAccumFrames = minAccumFrames) {

    @inline
    protected[this] def toFrame(f: Frame): Frame = f

    protected[this] def accumStream(frames: Queue[Frame]): StreamAccum = {
      var dataq = mutable.Queue.empty[Frame.Data]
      var dataEos = false
      var trailers: Frame.Trailers = null

      val iter = frames.iterator
      while (!dataEos && trailers == null && iter.hasNext) {
        iter.next() match {
          case d: Frame.Data =>
            dataq.enqueue(d)
            dataEos = d.isEnd

          case t: Frame.Trailers =>
            trailers = t
        }
      }

      val data: Frame.Data =
        if (dataq.isEmpty) null
        else new Frame.Data {
          def buf: Buf = dataq.foldLeft(Buf.Empty) { (buf, f) => buf.concat(f.buf) }
          def release() = Future.collect(dataq.map(_.release())).unit
          def isEnd = dataEos
        }

      mkStreamAccum(data, trailers)
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
}

object Frame {
  /**
   * A frame containing aribtrary data.
   *
   * `release()` MUST be called so that the producer may manage flow control.
   */
  trait Data extends Frame {
    def buf: Buf
    def release(): Future[Unit]
  }

  object Data {
    def apply(buf0: Buf, eos: Boolean): Data = new Data {
      def buf = buf0
      def release() = Future.Unit
      def isEnd = eos
    }

    def apply(s: String, eos: Boolean): Data =
      apply(Buf.Utf8(s), eos)

    def eos(buf: Buf): Data = apply(buf, true)
    def eos(s: String): Data = apply(s, true)
  }

  object Empty extends Data {
    def buf = Buf.Empty
    def release() = Future.Unit
    def isEnd = true
  }

  /** A terminal Frame including headers. */
  trait Trailers extends Frame with Headers { headers =>
    override def toString = s"Stream.Trailers(${headers.toSeq})"
    final override def isEnd = true
  }
}
