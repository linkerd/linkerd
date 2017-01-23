package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.netty4.ByteBufAsBuf
import com.twitter.util.{Future, Promise}
import io.netty.handler.codec.http2._
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

private[h2] object Netty4Message {

  trait Headers extends h2.Headers {
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

    def extract(orig: h2.Headers): Http2Headers = orig match {
      case orig: Headers => orig.underlying
      case orig =>
        val headers = new DefaultHttp2Headers
        for ((k, v) <- orig.toSeq) headers.add(k, v)
        headers
    }
  }

  object Request {
    def apply(netty4Headers: Http2Headers, data: Stream): h2.Request =
      h2.Request(Headers(netty4Headers), data)
  }

  object Response {
    def apply(netty4Headers: Http2Headers, data: Stream): h2.Response =
      h2.Response(Headers(netty4Headers), data)

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

  object Reset {

    def fromFrame(err: Http2ResetFrame): h2.Reset =
      fromCode(err.errorCode)

    def fromCode(code: Long): h2.Reset = Http2Error.valueOf(code) match {
      case Http2Error.CANCEL => h2.Reset.Cancel
      case Http2Error.ENHANCE_YOUR_CALM => h2.Reset.EnhanceYourCalm
      case Http2Error.INTERNAL_ERROR => h2.Reset.InternalError
      case Http2Error.NO_ERROR => h2.Reset.NoError
      case Http2Error.PROTOCOL_ERROR => h2.Reset.ProtocolError
      case Http2Error.REFUSED_STREAM => h2.Reset.Refused
      case Http2Error.COMPRESSION_ERROR => h2.Reset.CompressionError
      case Http2Error.CONNECT_ERROR => h2.Reset.ConnectError
      case Http2Error.FLOW_CONTROL_ERROR => h2.Reset.FlowControlError
      case Http2Error.INADEQUATE_SECURITY => h2.Reset.InadequateSecurity
      case Http2Error.SETTINGS_TIMEOUT => h2.Reset.SettingsTimeout
      case Http2Error.STREAM_CLOSED => h2.Reset.StreamClosed
      case err => throw new IllegalArgumentException(s"invalid stream error: ${err}")
    }

    def toHttp2Error(rst: h2.Reset): Http2Error = rst match {
      case h2.Reset.Cancel => Http2Error.CANCEL
      case h2.Reset.Closed => Http2Error.STREAM_CLOSED
      case h2.Reset.EnhanceYourCalm => Http2Error.ENHANCE_YOUR_CALM
      case h2.Reset.InternalError => Http2Error.INTERNAL_ERROR
      case h2.Reset.NoError => Http2Error.NO_ERROR
      case h2.Reset.ProtocolError => Http2Error.PROTOCOL_ERROR
      case h2.Reset.Refused => Http2Error.REFUSED_STREAM
      case h2.Reset.DoesNotExist => Http2Error.REFUSED_STREAM
      case h2.Reset.CompressionError => Http2Error.COMPRESSION_ERROR
      case h2.Reset.ConnectError => Http2Error.CONNECT_ERROR
      case h2.Reset.FlowControlError => Http2Error.FLOW_CONTROL_ERROR
      case h2.Reset.InadequateSecurity => Http2Error.INADEQUATE_SECURITY
      case h2.Reset.SettingsTimeout => Http2Error.SETTINGS_TIMEOUT
      case h2.Reset.StreamClosed => Http2Error.STREAM_CLOSED
    }
  }

}
