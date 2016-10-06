package com.twitter.finagle.buoyant.h2
package netty4

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

object Netty4ServerStreamTransport {
  private val log = Logger.get(getClass.getName)

  // Stub; actual values are set by the underlying transport.
  private val StubStreamId = -1

  private val isEnd: Http2StreamFrame => Boolean = {
    case f: Http2HeadersFrame => f.isEndStream
    case f: Http2DataFrame => f.isEndStream
    case _ => false
  }

  object OfferFailed extends Exception("Failed to offer a frame")
}

/**
 * Models a single Http/2 stream as a transport.
 */
class Netty4ServerStreamTransport(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
  minAccumFrames: Int = Int.MaxValue,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends ServerStreamTransport {

  import Netty4ServerStreamTransport._

  private[this] val writer = Netty4H2Writer(transport)

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

  private[this] val streamStats = statsReceiver.scope("stream")

  def onClose: Future[Throwable] = transport.onClose
  def close(deadline: Time): Future[Unit] = transport.close(deadline)

  /** Read the Request from the transport. */
  def read(): Future[Request] =
    transport.read().map(toRequest)

  private[this] val toRequest: Http2StreamFrame => Request = {
    case f: Http2HeadersFrame if f.isEndStream =>
      Netty4Message.Request(f.headers, Stream.Nil)

    case f: Http2HeadersFrame =>
      val data = newStream()
      readStream(data)
      Netty4Message.Request(f.headers, data)

    case f =>
      throw new IllegalStateException(s"Read unexpected ${f.name}; expected HEADERS")
  }

  private[this] def newStream() =
    new Netty4Stream(releaser, minAccumFrames, stats = streamStats)

  private[this] val releaser: Int => Future[Unit] =
    incr => writer.updateWindow(StubStreamId, incr)

  /**
   * Read data (and trailer) frames from the transport to the recvq
   * until an end-of-stream frame is encountered.
   */
  private[this] def readStream(stream: Stream.Writer[Http2StreamFrame]): Future[Unit] = {
    lazy val enqueueLoop: Http2StreamFrame => Future[Unit] = { f =>
      if (!stream.write(f)) Future.exception(OfferFailed)
      else if (isEnd(f)) Future.Unit
      else transport.read().flatMap(enqueueLoop)
    }

    val t0 = Stopwatch.start()
    val looping = transport.read().flatMap(enqueueLoop)
    looping.ensure(requestDurations.add(t0().inMillis))
    looping
  }

  def write(rsp: Response): Future[Future[Unit]] =
    writer.write(StubStreamId, rsp)

  private[this] def readFrame(data: Stream.Reader): Future[Frame] = {
    val t0 = Stopwatch.start()
    val rx = data.read()
    rx.ensure(streamReadMicros.add(t0().inMicroseconds))
    rx
  }

  private[this] def writeHeaders(hs: Headers, eos: Boolean): Future[Unit] =
    writer.write(StubStreamId, hs, eos)

}
