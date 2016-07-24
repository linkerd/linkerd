package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.CancelledRequestException
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.io.{Buf, Reader, Writer}
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Promise, Return, Stopwatch, Throw, Time}
import io.netty.buffer.{ByteBuf, CompositeByteBuf}
import io.netty.handler.codec.http2._
import scala.collection.immutable.Queue
import scala.collection.mutable.ListBuffer

object ServerStreamTransport {

  private val log = Logger.get(getClass.getName)

  private def getHeaders(hs: Headers): Http2Headers = hs match {
    case hs: Netty4Headers => hs.underlying
    case hs =>
      val headers = new DefaultHttp2Headers
      for ((k, v) <- hs.toSeq) headers.add(k, v)
      headers
  }

  private class FrameData(frame: Http2DataFrame) extends DataStream.Data {
    def buf = ByteBufAsBuf.Owned(frame.content.retain())
    def isEnd = frame.isEndStream
    def release(): Future[Unit] = {
      val _ = frame.release()
      Future.Unit
    }
  }

}

/**
 * Models a single Http/2 stream as a transport.
 */
class ServerStreamTransport(
  transport: Http2Transport,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Closable {

  // Note: Stream IDs are set by the underling transport.

  import ServerStreamTransport._

  /*
   * Stats
   */

  private[this] val requestDurations = statsReceiver.stat("request_duration_ms")
  private[this] val responseDurations = statsReceiver.stat("response_duration_ms")

  private[this] val readMs = statsReceiver.stat("read_ms")
  private[this] val writeMs = statsReceiver.stat("write_ms")
  private[this] val releaseMs = statsReceiver.stat("release_ms")

  private[this] val recvqSizes = statsReceiver.stat("recvq")
  private[this] val sendqReadMicros = statsReceiver.stat("sendq_poll_us")

  def close(deadline: Time): Future[Unit] =
    transport.close(deadline)

  /** Read the Request from the transport. */
  def read(): Future[Request] =
    transport.read().map(toRequest)

  private[this] val toRequest: Http2StreamFrame => Request = {
    case f: Http2HeadersFrame if f.isEndStream =>
      Request(RequestHeaders(f.headers))

    case f: Http2HeadersFrame =>
      val t = Stopwatch.start()
      val recvq = new AsyncQueue[Http2StreamFrame]
      val readF = readStream(recvq)
      readF.onSuccess(_ => requestDurations.add(t().inMillis))
      val stream = new Http2FrameDataStream(recvq, releaser)
      Request(RequestHeaders(f.headers), Some(stream))

    case f =>
      throw new IllegalStateException(s"Read unexpected ${f.name}; expected HEADERS")
  }

  /**
   * Read data (and trailer) frames from the transport until an
   * end-of-stream frame is encountered.
   */
  private[this] def readStream(recvq: AsyncQueue[Http2StreamFrame]): Future[Unit] = {
    lazy val enqueueAndLoop: Http2StreamFrame => Future[Unit] = { f =>
      val isEnd = f match {
        case f: Http2HeadersFrame => f.isEndStream
        case f: Http2DataFrame => f.isEndStream
        case _ => false
      }
      recvq.offer(f)
      if (isEnd) Future.Unit else loop()
    }
    def loop(): Future[Unit] =
      transport.read().flatMap(enqueueAndLoop)

    loop()
  }

  private[this] val streamId = -1 // Set by the transport.

  def write(rsp: Response): Future[Future[Unit]] =
    writeHeaders(rsp.headers, rsp.data.isEmpty).map { _ =>
      rsp.data match {
        case None => Future.Unit
        case Some(data) =>
          val dataStart = Stopwatch.start()
          val writing = streamFrom(data)
          writing.onSuccess(_ => responseDurations.add(dataStart().inMillis))
          writing
      }
    }

  private[this] def streamFrom(data: DataStream): Future[Unit] = {
    lazy val loopUnless: Boolean => Future[Unit] = { eos =>
      if (eos) Future.Unit
      else loop()
    }
    def loop(): Future[Unit] = {
      val sinceReadStart = Stopwatch.start()
      val readF = data.read()
      readF.onSuccess(_ => sendqReadMicros.add(sinceReadStart().inMicroseconds))
      readF.flatMap(writeData).flatMap(loopUnless)
    }

    loop()
  }

  private[this] val releaser: Int => Future[Unit] =
    incr => transport.writeWindowUpdate(incr, streamId)

  private[this] def writeHeaders(h: Headers, eos: Boolean): Future[Boolean] =
    transport.writeHeaders(h, streamId, eos).map(_ => eos)

  private[this] def writeTrailers(h: Headers): Future[Boolean] =
    transport.writeTrailers(h, streamId).before(Future.True)

  private[this] val writeData: DataStream.Value => Future[Boolean] = {
    case data: DataStream.Data =>
      transport.writeData(data, streamId).before(data.release()).map(_ => data.isEnd)
    case DataStream.Trailers(tlrs) =>
      transport.writeTrailers(tlrs, streamId).before(Future.True)
  }

}
