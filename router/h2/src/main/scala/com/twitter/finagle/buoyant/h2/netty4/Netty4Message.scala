package com.twitter.finagle.buoyant.h2
package netty4

import io.netty.handler.codec.http2.{DefaultHttp2Headers, Http2Headers}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

private[h2] object Netty4Message {

  class Netty4Headers(underlying: Http2Headers) extends Headers {

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


    override def get(key: String): Seq[String] = underlying.getAll(key).asScala.map(_.toString)
    override def add(key: String, value: String): Unit = { underlying.add(key, value); () }
    override def set(key: String, value: String): Unit = { underlying.set(key, value); () }
    override def remove(key: String): Boolean = underlying.remove(key)
    override def copy(): Netty4Headers = {}
  }

  def extract(msg: Headers): Http2Headers = msg match {
    case orig: Netty4Headers => orig.netty4Headers
    case msg =>
      val headers = new DefaultHttp2Headers
      for ((k, v) <- msg.headers) headers.add(k, v)
      headers
  }

  case class Request(netty4Headers: Http2Headers, data: DataStream)
    extends com.twitter.finagle.buoyant.h2.Request {

    require(netty4Headers.scheme != null)
    def scheme = netty4Headers.scheme.toString

    require(netty4Headers.method != null)
    def method = netty4Headers.method.toString

    require(netty4Headers.path != null)
    def path = netty4Headers.path.toString

    require(netty4Headers.authority != null)
    def authority = netty4Headers.authority.toString
  }

  case class Response(netty4Headers: Http2Headers, dataStream: DataStream)
    extends com.twitter.finagle.buoyant.h2.Response
    with DataStream.Proxy with Netty4Headers {

    require(netty4Headers.status != null)
    def status = netty4Headers.status.toString.toInt
  }

  case class Trailers(netty4Headers: Http2Headers)
    extends DataStream.Trailers
    with Netty4Headers
}
