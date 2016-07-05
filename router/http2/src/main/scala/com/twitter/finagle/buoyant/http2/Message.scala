package com.twitter.finagle.buoyant.http2

import com.twitter.io.Reader
import com.twitter.util.Future
import io.netty.handler.codec.http2.{DefaultHttp2Headers, Http2Headers}
import scala.collection.mutable.ListBuffer

trait Header {
  def key: String
  def value: String
}
sealed trait Headers {
  def toSeq: Seq[(String, String)]
}

private[http2] trait Netty4Headers { self: Headers =>
  def underlying: Http2Headers

  def toSeq = {
    val buf = ListBuffer.newBuilder[(String, String)]
    buf.sizeHint(underlying.size)
    val iter = underlying.iterator
    while (iter.hasNext) {
      val entry = iter.next()
      buf += entry.getKey.toString -> entry.getValue.toString
    }
    buf.result
  }
}

object Headers {
  private[http2] def apply(headers: Http2Headers): Headers =
    new Netty4Headers with Headers {
      val underlying = headers
    }
}

trait RequestHeaders extends Headers {
  def scheme: String
  def method: String
  def path: String
  def authority: String
}

object RequestHeaders {

  private[http2] def apply(headers: Http2Headers): RequestHeaders = {
    require(headers.scheme != null)
    require(headers.method != null)
    require(headers.path != null)
    require(headers.authority != null)
    new Netty4Headers with RequestHeaders {
      val underlying = headers
      def scheme = headers.scheme.toString
      def method = headers.method.toString
      def path = headers.path.toString
      def authority = headers.authority.toString
    }
  }

  def apply(
    scheme: String,
    method: String,
    path: String,
    authority: String,
    headers: Seq[(String, String)] = Nil
  ): RequestHeaders = {
    val h = new DefaultHttp2Headers
    h.scheme(scheme)
    h.method(method)
    h.path(path)
    h.authority(authority)
    for ((k, v) <- headers) h.add(k, v)
    apply(h)
  }
}

trait ResponseHeaders extends Headers {
  def status: Int
}

object ResponseHeaders {

  private[http2] def apply(headers: Http2Headers): ResponseHeaders = {
    require(headers.status != null)
    new Netty4Headers with ResponseHeaders {
      val underlying = headers
      def status = headers.status.toString.toInt
    }
  }

  def apply(
    status: Int,
    headers: Seq[(String, String)] = Nil
  ): ResponseHeaders = {
    val h = new DefaultHttp2Headers
    h.status(status.toString)
    for ((k, v) <- headers) h.add(k, v)
    apply(h)
  }
}

case class DataStream(
  reader: Reader,
  trailers: Future[Option[Headers]] = Future.value(None)
)

/**
 * An HTTP2 message.
 *
 * HTTP2 Messages consist of three components:
 * - Headers
 * - Data
 * - Trailers
 *
 * Headers are required; and Data and Trailers may be empty.
 */
sealed trait Message {
  def headers: Headers
  def data: Option[DataStream]
}

case class Request(
  headers: RequestHeaders,
  data: Option[DataStream] = None
) extends Message

case class Response(
  headers: ResponseHeaders,
  data: Option[DataStream] = None
) extends Message
