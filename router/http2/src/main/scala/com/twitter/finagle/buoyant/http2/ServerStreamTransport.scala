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

object ServerStreamTransport {

  private val log = Logger.get(getClass.getName)

  private def getHeaders(hs: Headers): Http2Headers = hs match {
    case hs: Netty4Headers => hs.underlying
    case hs =>
      val headers = new DefaultHttp2Headers
      for ((k, v) <- hs.toSeq) headers.add(k, v)
      headers
  }
}

/**
 * Models a single Http/2 stream as a transport.
 */
class ServerStreamTransport(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Closable {

  // Stream IDs are set by the underling transport

  import ServerStreamTransport._

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

  private[this] def readFrame(): Future[Http2StreamFrame] = {
    val t = Stopwatch.start()
    transport.read().onSuccess(_ => readMs.add(t().inMillis))
  }

  private[this] def writeFrame(f: Http2StreamFrame): Future[Unit] = {
    val t = Stopwatch.start()
    transport.write(f).onSuccess(_ => writeMs.add(t().inMillis))
  }

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
          val data = new DataStream.Data {
            def buf = ByteBufAsBuf.Owned(f.content.retain())
            def isEnd = f.isEndStream
            def release(): Future[Unit] = {
              val _ = f.release()
              Future.Unit
            }
          }
          recvq.offer(data)
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

  private[this] def streamFrom(data: DataStream): Future[Unit] = {
    def loop(): Future[Unit] = {
      val readW = Stopwatch.start()
      val read = data.read()
      read.onSuccess { vs =>
        sendqReadMicros.add(readW().inMicroseconds)
        sendqSizes.add(vs.size)
      }
      read.flatMap {
        case values if values.isEmpty => loop()

        case Seq(v) => v match {
          case data: DataStream.Data =>
            val wrote = {
              val frame = new DefaultHttp2DataFrame(BufAsByteBuf.Owned(data.buf), data.isEnd)
              writeFrame(frame).before {
                val releaseW = Stopwatch.start()
                val released = data.release()
                released.onSuccess(_ => releaseMs.add(releaseW().inMillis))
                released
              }
            }
            if (data.isEnd) wrote
            else wrote.before(loop())

          case DataStream.Trailers(trailers) => writeTrailers(trailers)
        }

        case values =>
          writeAggregatedDataStream(values).flatMap { eos =>
            if (eos) Future.Unit
            else loop()
          }
      }
    }

    loop()
  }

  private[this] def writeHeaders(h: Headers, eos: Boolean): Future[Unit] =
    writeFrame(new DefaultHttp2HeadersFrame(getHeaders(h), eos))

  private[this] def writeTrailers(h: Headers): Future[Unit] =
    writeHeaders(h, true)

  private[this] def writeAggregatedDataStream(vals: Seq[DataStream.Value]): Future[Boolean] = {
    var i = 0
    var trailers: Headers = null
    var eos = false
    var release: () => Future[Unit] = () => Future.Unit
    var compositeBuf: CompositeByteBuf = null
    while (i != vals.size && trailers == null) {
      vals(i) match {
        case data: DataStream.Data =>
          val bb = BufAsByteBuf.Owned(data.buf)
          if (compositeBuf == null) {
            compositeBuf = bb.alloc.compositeBuffer(vals.size)
            compositeBuf.markReaderIndex()
          }
          compositeBuf.addComponent(true, bb)
          eos = data.isEnd

          val releasePrior = release
          release = () => releasePrior().before(data.release())

          i += 1
        case DataStream.Trailers(ts) =>
          trailers = ts
      }
    }

    val dataWrite =
      if (i > 1 || trailers == null) {
        compositeBuf.resetReaderIndex()
        writeFrame(new DefaultHttp2DataFrame(compositeBuf.retain(), eos)).before {
          val releaseW = Stopwatch.start()
          val released = release()
          compositeBuf.release()
          released.onSuccess(_ => releaseMs.add(releaseW().inMillis))
          released
        }
      } else Future.Unit

    trailers match {
      case null =>
        dataWrite.map(_ => eos)

      case trailers =>
        dataWrite.before(writeTrailers(trailers)).map(_ => false)
    }
  }
}
