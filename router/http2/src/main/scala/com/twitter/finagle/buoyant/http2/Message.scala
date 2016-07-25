package com.twitter.finagle.buoyant.http2

import com.twitter.io.Buf
import com.twitter.util.Future

sealed trait Headers {
  def headers: Seq[(String, String)]
}

/**
 * A generic HTTP2 message.
 *
 * Comprises initial headers, optionally followed by a data stream,
 * optionally followed by trailers.
 *
 * These types are only intended to provide a session layer and are
 * not intended to provide programmer-friendly niceties.
 */
sealed trait Message extends Headers with DataStream {
  def isEmpty: Boolean
  final def nonEmpty: Boolean = !isEmpty
}

trait Request extends Message {
  def scheme: String
  def method: String
  def authority: String
  def path: String
}

trait Response extends Message {
  def status: Int
}

/**
 * A readable stream of Data or Trailers frames.
 */
trait DataStream {
  override def toString = s"DataStream(isEmpty=$isEmpty)"
  def isEmpty: Boolean
  def onEnd: Future[Unit]
  def read(): Future[DataStream.Frame]
  def fail(exn: Throwable): Unit
}

object DataStream {

  trait Proxy extends DataStream {
    def data: DataStream
    def isEmpty = data.isEmpty
    def onEnd = data.onEnd
    def read() = data.read()
    def fail(exn: Throwable) = data.fail(exn)
  }

  /**
   * An empty stream. Useful, for instance, when a Message consists of
   * only a headers frame with an
   */
  trait Nil extends DataStream {
    override def toString = "DataStream.Nil"
    def isEmpty = true
    def onEnd = Future.Unit
    def read() = Future.never
    def fail(exn: Throwable) = {}
  }
  object Nil extends Nil

  /**
   */
  sealed trait Frame {
    def isEnd: Boolean
  }

  /**
   */
  trait Data extends Frame {
    override def toString = s"DataStream.Data(buf=$buf, isEnd=$isEnd)"
    def buf: Buf
    def release(): Future[Unit]
  }

  trait Eos extends Data {
    override def toString = "DataStream.Eos"
    def isEnd = true
    def release() = Future.Unit
  }

  object Eos extends Eos { def buf = Buf.Empty }

  trait Trailers extends Frame with Headers {
    override def toString = s"DataStream.Trailers($headers)"
    val isEnd = true
  }
}
