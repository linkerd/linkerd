package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.buoyant.h2.{Request => H2Request, Response => H2Response, Headers => H2Headers}
import com.twitter.finagle.netty4.ByteBufAsBuf
import com.twitter.util.{Future, Promise}
import io.netty.handler.codec.http2.{DefaultHttp2Headers, Http2Headers, Http2DataFrame}
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

    override def contains(key: String): Boolean =
      underlying.contains(key)

    override def add(key: String, value: String): Unit = {
      underlying.add(key, value); ()
    }

    override def set(key: String, value: String): Unit = {
      underlying.set(key, value); ()
    }

    override def remove(key: String): Seq[String] = {
      val removed = get(key)
      underlying.remove(key)
      removed
    }

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

  object Request {
    def apply(netty4Headers: Http2Headers, data: Stream): H2Request =
      H2Request(Headers(netty4Headers), data)
  }

  object Response {
    def apply(netty4Headers: Http2Headers, data: Stream): H2Response =
      H2Response(Headers(netty4Headers), data)

    def apply(status: Status, stream: Stream): Response = {
      val h = new DefaultHttp2Headers
      h.status(status.toString)
      apply(h, stream)
    }
  }

  object Data {

    def apply(f: Http2DataFrame, updateWindow: Int => Future[Unit]): Frame.Data = {
      val sz = f.content.readableBytes + f.padding
      val buf = ByteBufAsBuf.Owned(f.content.retain())
      val releaser: () => Future[Unit] =
        if (sz > 0) () => updateWindow(sz)
        else () => Future.Unit
      Frame.Data(buf, f.isEndStream, releaser)
    }
  }

  case class Trailers(underlying: Http2Headers)
    extends Frame.Trailers
    with Headers {

    private[this] val releaseP = new Promise[Unit]
    override def onRelease: Future[Unit] = releaseP
    override def release() = {
      releaseP.setDone()
      Future.Unit
    }
  }
}
