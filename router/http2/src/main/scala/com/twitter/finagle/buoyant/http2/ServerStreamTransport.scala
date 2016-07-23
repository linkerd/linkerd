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

  private case class StreamAccum(
    data: Option[AccumData] = None,
    trailers: Option[Headers] = None
  )
  private case class AccumData(content: ByteBuf, eof: Boolean)

  private def accumStream(q: Queue[DataStream.Value]): StreamAccum = {
    var content: CompositeByteBuf = null
    var eos = false
    var trailers: Option[Headers] = None

    val iter = q.iterator
    while (iter.hasNext && !eos && trailers == null) {
      iter.next() match {
        case data: DataStream.Data =>
          val bb = BufAsByteBuf.Owned(data.buf)
          if (content == null) {
            content = bb.alloc.compositeBuffer(q.length)
          }
          content.addComponent(true, bb)
          eos = data.isEnd

        case DataStream.Trailers(ts) =>
          trailers = Some(ts)
      }
    }

    val data = if (content == null) None else Some(AccumData(content, eos))
    StreamAccum(data, trailers)
  }

  private def release(q: Queue[DataStream.Value]): Future[Unit] =
    if (q.isEmpty) Future.Unit
    else q.dequeue match {
      case (data: DataStream.Data, tl) => data.release().before(release(tl))
      case (_, tl) => release(tl)
    }

}

/**
 * Models a single Http/2 stream as a transport.
 */
class ServerStreamTransport(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
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
  private[this] val sendqSizes = statsReceiver.stat("sendq")
  private[this] val sendqReadMicros = statsReceiver.stat("sendq_poll_us")

  def close(deadline: Time): Future[Unit] =
    transport.close(deadline)

  /** Read the Request from the transport. */
  def read(): Future[Request] = {
    readFrame().flatMap {
      case f: Http2HeadersFrame if f.isEndStream =>
        Future.value(Request(RequestHeaders(f.headers)))

      case f: Http2HeadersFrame =>
        val t = Stopwatch.start()
        val recvq = new AsyncQueue[DataStream.Value]
        val reading = readStream(recvq)
        reading.onSuccess(_ => requestDurations.add(t().inMillis))
        val stream = new AQDataStream(recvq)
        Future.value(Request(RequestHeaders(f.headers), Some(stream)))

      case f =>
        val e = new IllegalStateException(s"Read unexpected ${f.name}; expected HEADERS")
        Future.exception(e)
    }
  }

  /**
   * Read data (and trailer) frames from the transport until an
   * end-of-stream frame is encountered.
   */
  private[this] def readStream(recvq: AsyncQueue[DataStream.Value]): Future[Unit] = {
    def loop(): Future[Unit] =
      readFrame().flatMap {
        case f: Http2DataFrame =>
          recvq.offer(new FrameData(f))
          if (f.isEndStream) Future.Unit else loop()

        case f: Http2HeadersFrame if f.isEndStream =>
          recvq.offer(DataStream.Trailers(Headers(f.headers)))
          Future.Unit

        case f =>
          val e = new IllegalStateException(s"Read unexpected ${f.name}; expected DATA or HEADERS")
          recvq.fail(e)
          Future.exception(e)
      }

    loop()
  }

  def write(rsp: Response): Future[Future[Unit]] = {
    val f = new DefaultHttp2HeadersFrame(getHeaders(rsp.headers), rsp.data.isEmpty)
    val writeStart = Stopwatch.start()
    writeFrame(f).map { _ =>
      rsp.data match {
        case None => Future.Unit
        case Some(data) =>
          val dataStart = Stopwatch.start()
          val writing = streamFrom(data)
          writing.onSuccess(_ => responseDurations.add(dataStart().inMillis))
          writing
      }
    }
  }

  private[this] def readFrame(): Future[Http2StreamFrame] = {
    val t = Stopwatch.start()
    transport.read().onSuccess(_ => readMs.add(t().inMillis))
  }

  private[this] def writeFrame(f: Http2StreamFrame): Future[Unit] = {
    val t = Stopwatch.start()
    transport.write(f).onSuccess(_ => writeMs.add(t().inMillis))
  }

  private[this] def streamFrom(data: DataStream): Future[Unit] = {
    def loop(): Future[Unit] = {
      val sinceReadStart = Stopwatch.start()
      val read = data.read()

      read.onSuccess { vs =>
        sendqReadMicros.add(sinceReadStart().inMicroseconds)
        sendqSizes.add(vs.size)
      }

      read.flatMap(writeData).flatMap {
        case true => Future.Unit
        case false => loop()
      }
    }

    loop()
  }

  private[this] def writeHeaders(h: Headers, eos: Boolean): Future[Boolean] =
    writeFrame(new DefaultHttp2HeadersFrame(getHeaders(h), eos)).before(Future.True)

  private[this] def writeTrailers(h: Headers): Future[Boolean] =
    writeHeaders(h, true)

  private[this] def writeData(
    content: ByteBuf,
    eos: Boolean,
    release: () => Future[Unit]
  ): Future[Boolean] =
    writeFrame(new DefaultHttp2DataFrame(content, eos)).before {
      val releaseStart = Stopwatch.start()
      val released = release()
      released.onSuccess(_ => releaseMs.add(releaseStart().inMillis))
      released
    }.map(_ => eos)

  private[this] def writeData(data: DataStream.Data): Future[Boolean] =
    writeData(BufAsByteBuf.Owned(data.buf), data.isEnd, data.release)

  private[this] def write(v: DataStream.Value): Future[Boolean] = v match {
    case data: DataStream.Data =>
      writeData(BufAsByteBuf.Owned(data.buf), data.isEnd, data.release)
    case DataStream.Trailers(trailers) =>
      writeTrailers(trailers)
  }

  private[this] val writeData: Queue[DataStream.Value] => Future[Boolean] = {
    case q if q.isEmpty => Future.False

    case q if q.length == 1 =>
      val (v, _) = q.dequeue
      write(v)

    case q =>
      val StreamAccum(data, trailers) = accumStream(q)
      val wroteData = data match {
        case None => Future.False
        case Some(AccumData(content, eos)) =>
          writeData(content, eos, () => release(q))
      }
      trailers match {
        case None => wroteData
        case Some(trailers) =>
          wroteData.flatMap(_ => writeTrailers(trailers))
      }
  }

}
