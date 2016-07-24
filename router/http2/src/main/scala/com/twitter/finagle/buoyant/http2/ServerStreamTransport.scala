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

  // Stub; actual values are set by the underlying transport.
  private val streamId = -1
}

/**
 * Models a single Http/2 stream as a transport.
 */
class ServerStreamTransport(
  transport: Http2Transport,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Closable {

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
  private[this] val streamReadMicros = statsReceiver.stat("stream_read_us")

  def close(deadline: Time): Future[Unit] =
    transport.close(deadline)

  /** Read the Request from the transport. */
  def read(): Future[Request] =
    transport.read().map(toRequest)

  private[this] val toRequest: Http2StreamFrame => Request = {
    case f: Http2HeadersFrame if f.isEndStream =>
      Request(RequestHeaders(f.headers))

    case f: Http2HeadersFrame =>
      val recvq = new AsyncQueue[Http2StreamFrame]
      val readF = readStream(recvq)
      val stream = new Http2FrameDataStream(recvq, releaser)
      Request(RequestHeaders(f.headers), stream)

    case f =>
      throw new IllegalStateException(s"Read unexpected ${f.name}; expected HEADERS")
  }

  private[this] val releaser: Int => Future[Unit] =
    incr => transport.writeWindowUpdate(incr, streamId)

  /**
   * Read data (and trailer) frames from the transport until an
   * end-of-stream frame is encountered.
   */
  private[this] def readStream(recvq: AsyncQueue[Http2StreamFrame]): Future[Unit] = {
    lazy val enqueueLoop: Http2StreamFrame => Future[Unit] = { f =>
      val eos = isEnd(f)
      recvq.offer(f)
      if (eos) Future.Unit
      else transport.read().flatMap(enqueueLoop)
    }

    val t0 = Stopwatch.start()
    val looping = transport.read().flatMap(enqueueLoop)
    looping.onSuccess(_ => requestDurations.add(t0().inMillis))
    looping
  }

  private[this] val isEnd: Http2StreamFrame => Boolean = {
    case f: Http2HeadersFrame => f.isEndStream
    case f: Http2DataFrame => f.isEndStream
    case _ => false
  }

  def write(rsp: Response): Future[Future[Unit]] =
    writeHeaders(rsp.headers, rsp.data.isEmpty).map(_ => streamFrom(rsp.data))

  private[this] def streamFrom(data: DataStream): Future[Unit] =
    if (data.isEmpty) Future.Unit
    else {
      def read(): Future[DataStream.Value] = {
        val t0 = Stopwatch.start()
        val f = data.read()
        f.onSuccess(_ => streamReadMicros.add(t0().inMicroseconds))
        f
      }

      lazy val loop: Boolean => Future[Unit] = { eos =>
        if (data.isEmpty || eos) Future.Unit
        else read().flatMap(writeData).flatMap(loop)
      }

      val t0 = Stopwatch.start()
      val looping = read().flatMap(writeData).flatMap(loop)
      looping.onSuccess(_ => responseDurations.add(t0().inMillis))
      looping
    }

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
