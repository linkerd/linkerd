package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.finagle.Failure
import com.twitter.util.Future

/**
 * A generic HTTP2 message.
 *
 * The message itself includes the Headers that initiated the message
 * (comprising all HEADERS frames sent to initiate the message).
 *
 * Furthermore, messages have an (optional)
 *
 * These types are only intended to satisfy session layer concerned
 * and are not intended to provide a rish programming interface.
 * However, it is hoped that other types may be built upon / adapated
 * to satisfy a broader set of use cases.
 */
sealed trait Message {
  def headers: Headers
  def data: Stream
  def dup(): Message
}

/*
 * Both 
 */
trait Headers {
  def toSeq: Seq[(String, String)]
  def get(k: String): Seq[String]
  def add(k: String, v: String): Unit
  def set(k: String, v: String): Unit
  def remove(key: String): Boolean
  def dup(): Headers
}

/**
 * Request messages include helpers for accessing pseudo-headers.
 */
trait Request extends Message {
  def scheme: String
  def method: String
  def authority: String
  def path: String
  override def dup(): Request
}

/**
 * Request messages includes a helper for accessing the response
 * psuedo-header.
 */
trait Response extends Message {
  def status: Int
  override def dup(): Response
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

  val ClosedFailure = Failure("Stream is closed")

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

  /**
   * In order to create a stream, we need a mechanism to write to it.
   */
  trait Writer[T] {
    def write(frame: T): Boolean
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
