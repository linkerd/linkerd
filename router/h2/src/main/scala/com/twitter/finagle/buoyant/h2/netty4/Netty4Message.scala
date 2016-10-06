package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.buoyant.h2.{Request => H2Request, Response => H2Response, Headers => H2Headers}
import io.netty.handler.codec.http2.{DefaultHttp2Headers, Http2Headers}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

private[h2] object Netty4Message {

  trait Headers extends H2Headers {
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

    override def get(key: String): Seq[String] =
      underlying.getAll(key).asScala.map(_.toString)

    override def add(key: String, value: String): Unit = {
      underlying.add(key, value); ()
    }

    override def set(key: String, value: String): Unit = {
      underlying.set(key, value); ()
    }

    override def remove(key: String): Boolean =
      underlying.remove(key)

    override def dup(): Headers = {
      val headers = new DefaultHttp2Headers
      val iter = underlying.iterator
      while (iter.hasNext) {
        val kv = iter.next()
        headers.set(kv.getKey, kv.getValue)
      }
      Headers(headers)
    }

  }

  object Headers {
    def apply(h: Http2Headers): Headers =
      new Headers { val underlying = h }

    def extract(orig: H2Headers): Http2Headers = orig match {
      case orig: Headers => orig.underlying
      case orig =>
        val headers = new DefaultHttp2Headers
        for ((k, v) <- orig.toSeq) headers.add(k, v)
        headers
    }
  }

  case class Request(netty4Headers: Http2Headers, data: Stream)
    extends H2Request {

    require(netty4Headers.scheme != null)
    override def scheme = netty4Headers.scheme.toString

    require(netty4Headers.method != null)
    override def method = netty4Headers.method.toString

    require(netty4Headers.path != null)
    override def path = netty4Headers.path.toString

    require(netty4Headers.authority != null)
    override def authority = netty4Headers.authority.toString

    override val headers: Headers = Headers(netty4Headers)

    override def dup(): com.twitter.finagle.buoyant.h2.Request =
      copy(netty4Headers = Headers.extract(headers.dup()))
  }

  case class Response(netty4Headers: Http2Headers, data: Stream)
    extends H2Response {

    require(netty4Headers.status != null)
    def status = netty4Headers.status.toString.toInt

    override val headers: Headers = Headers(netty4Headers)

    override def dup(): com.twitter.finagle.buoyant.h2.Response =
      copy(netty4Headers = Headers.extract(headers.dup()))
  }

  case class Trailers(underlying: Http2Headers)
    extends Frame.Trailers with Headers
}
