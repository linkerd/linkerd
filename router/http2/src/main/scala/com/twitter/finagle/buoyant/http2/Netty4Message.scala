package com.twitter.finagle.buoyant.http2

import io.netty.handler.codec.http2.{DefaultHttp2Headers, Http2Headers}
import scala.collection.mutable.ListBuffer

private[http2] object Netty4Message {

  trait Netty4Headers { _: Headers =>
    def netty4Headers: Http2Headers

    def headers = {
      val buf = ListBuffer.newBuilder[(String, String)]
      buf.sizeHint(netty4Headers.size)
      val iter = netty4Headers.iterator
      while (iter.hasNext) {
        val entry = iter.next()
        buf += entry.getKey.toString -> entry.getValue.toString
      }
      buf.result
    }
  }

  def extract(msg: Headers): Http2Headers = msg match {
    case orig: Netty4Headers => orig.netty4Headers
    case msg =>
      val headers = new DefaultHttp2Headers
      for ((k, v) <- msg.headers) headers.add(k, v)
      headers
  }

  case class Request(netty4Headers: Http2Headers, data: DataStream)
    extends com.twitter.finagle.buoyant.http2.Request
    with DataStream.Proxy with Netty4Headers {

    require(netty4Headers.scheme != null)
    def scheme = netty4Headers.scheme.toString

    require(netty4Headers.method != null)
    def method = netty4Headers.method.toString

    require(netty4Headers.path != null)
    def path = netty4Headers.path.toString

    require(netty4Headers.authority != null)
    def authority = netty4Headers.authority.toString
  }

  case class Response(netty4Headers: Http2Headers, data: DataStream)
    extends com.twitter.finagle.buoyant.http2.Response
    with DataStream.Proxy with Netty4Headers {

    require(netty4Headers.status != null)
    def status = netty4Headers.status.toString.toInt
  }

  case class Trailers(netty4Headers: Http2Headers)
    extends DataStream.Trailers
    with Netty4Headers
}
