package com.twitter.finagle.buoyant.h2

import com.twitter.io.Buf
import com.twitter.util.Future
import scala.util.control.NoStackTrace

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
  def data: DataStream
  def isEmpty: Boolean
  final def nonEmpty: Boolean = !isEmpty
  def copy(): Message
}

/**
 * Request messages include helpers for accessing pseudo-headers.
 */
trait Request extends Message {
  def scheme: String
  def method: String
  def authority: String
  def path: String
  override def copy(): Request
}

/**
 * Request messages includes a helper for accessing the response
 * psuedo-header.
 */
trait Response extends Message {
  def status: Int
  override def copy(): Response
}

/*
 * Both Messages and Trailers include headers...
 */
sealed trait Headers {
  def toSeq: Seq[(String, String)]
  def get(key: String): Seq[String]
  def add(key: String, value: String): Unit
  def set(key: String, value: String): Unit
  def remove(key: String): Unit
  def copy(): Headers
}

/**
 * A DataStream represents a stream of Data frames, optionally
 * followed by Trailers.
 *
 * A DataStream is not prescriptive as to how data is
 * produced. However, flow control semantics are built into teh
 * DataStream--consumers MUST release each data frame after it has
 * processed its contents.
 */
trait DataStream {
  override def toString = s"DataStream(isEmpty=$isEmpty)"

  /** True if read() is expecetd to return more data. */
  def isEmpty: Boolean

  /** Satisfied when the stream completes */
  def onEnd: Future[Unit]

  /**
   * Read the next stream frame.
   *
   * Implementations may combined Data frames.
   */
  def read(): Future[DataStream.Frame]

  /** Cancel processing of the stream with the given failure. */
  def fail(exn: Throwable): Unit
}

object DataStream {

  class ClosedException extends Exception("DataStream is closed") with NoStackTrace

  /**
   * An empty stream. Useful, for instance, when a Message consists of
   * only a headers frame with END_STREAM set.
   */
  trait Nil extends DataStream {
    override def toString = "DataStream.Nil"
    def isEmpty = true
    def onEnd = Future.Unit
    def read() = Future.exception(new ClosedException)
    def fail(exn: Throwable) = {}
  }
  object Nil extends Nil

  /**
   * A single item in a DataStream.
   */
  sealed trait Frame {

    /**
     * When `isEnd` is true, no further events will be returned on the
     * DataStream.
     */
    def isEnd: Boolean
  }

  /**
   * A frame containing aribtrary data.
   *
   * `release()` MUST be called so that the producer may manage flow control.
   */
  trait Data extends Frame {
    override def toString = s"DataStream.Data(buf=$buf, isEnd=$isEnd)"
    def buf: Buf
    def release(): Future[Unit]
  }

  /** An empty, terminal DataStream frame. */
  trait Eos extends Data {
    override def toString = "DataStream.Eos"
    def buf = Buf.Empty
    def isEnd = true
    def release() = Future.Unit
  }
  object Eos extends Eos

  /** A terminal DataSTream frame including headers. */
  trait Trailers extends Frame with Headers {
    override def toString = s"DataStream.Trailers($headers)"
    val isEnd = true
  }

  trait Offerable[T] {
    def offer(frame: T): Boolean
  }
}
