package com.twitter.finagle.buoyant.h2

import com.twitter.util.{Future, Promise}

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
  def stream: Stream

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
  val ContentType = "content-type"

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
  private[h2] class Impl(orig: Seq[(String, String)]) extends Headers {
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
    stream: Stream
  ): Request = Impl(
    Headers(
      Headers.Scheme -> scheme,
      Headers.Method -> method.toString,
      Headers.Authority -> authority,
      Headers.Path -> path
    ),
    stream
  )

  def apply(headers: Headers, stream: Stream): Request =
    Impl(headers, stream)

  private case class Impl(headers: Headers, stream: Stream) extends Request {
    override def toString = s"Request($scheme, $method, $authority, $path, $stream)"
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

  def apply(status: Status, stream: Stream): Response =
    Impl(Headers(Headers.Status -> status.code.toString), stream)

  def apply(headers: Headers, stream: Stream): Response =
    Impl(headers, stream)

  private case class Impl(
    headers: Headers,
    stream: Stream
  ) extends Response {
    override def toString = s"Response($status, $stream)"
    override def dup() = copy(headers = headers.dup())
    override def status = headers.get(Headers.Status).headOption match {
      case Some(code) => Status.fromCode(code.toInt)
      case None => throw new IllegalArgumentException(s"missing ${Headers.Status} header")
    }
  }
}
