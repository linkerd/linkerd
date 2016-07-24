package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Service
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.io.{Buf, Reader, Writer}
import com.twitter.util.{Closable, Future, Promise, Return, Stopwatch, Time, Throw}
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}

object ClientDispatcher {
  private val log = Logger.get(getClass.getName)

  private val MaxStreamId = math.pow(2, 31) - 1
}

/**
 * Multiplexes HTTP/2 request/responses onto HTTP/2 streams over a
 * shared connection transport.
 */
class ClientDispatcher(
  transport: Http2Transport,
  minAccumFrames: Int = 10,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Service[Request, Response] {

  import ClientDispatcher._

  // TODO handle overflow
  private[this] val _id = new AtomicInteger(3) // ID=1 is reserved for HTTP/1 upgrade
  private[this] def nextId() = _id.getAndAdd(2)
  private[this] val liveStreams = new ConcurrentHashMap[Int, StreamTransport]

  private[this] val streamStats = statsReceiver.scope("streams")
  private[this] val streamStateStats = streamStats.scope("state")
  private[this] val recvqSizes = streamStats.stat("recvq")
  private[this] val requestDurations = statsReceiver.stat("request_duration_ms")
  private[this] val responseDurations = statsReceiver.stat("response_duration_ms")

  @volatile private[this] var closed = false
  override def close(d: Time): Future[Unit] = {
    closed = true
    transport.close()
  }

  /**
   * Write a request on the underlying connection and return its
   * response when it is received.
   */
  def apply(req: Request): Future[Response] = {
    // Initialize a new Stream; and store it so that a response may be
    // demultiplexed to it.
    val stream = StreamTransport(nextId())
    if (liveStreams.putIfAbsent(stream.id, stream) != null) {
      val e = new IllegalStateException(s"stream ${stream.id} already exists")
      return Future.exception(e)
    }

    req.data match {
      case None =>
        stream.writeHeaders(req.headers, eos = true).
          before(stream.readResponse())

      // New request, with data to be sent.
      case Some(data) =>
        stream.writeHeaders(req.headers).before {
          // Start reading a response...
          val readF = stream.readResponse()

          // Also, finish streaming the request data...
          val clock = Stopwatch.start()
          val writeF = stream.streamRequest(data)
          writeF.onSuccess(_ => requestDurations.add(clock().inMillis))

          // If the response fails, try to stop writing data.
          readF.onFailure(writeF.raise(_))

          // If the request fails, try to stop reading data.
          writeF.onFailure { e =>
            log.error(e, s"client.dispatch: ${stream.id} writing error")
            readF.raise(e)
          }

          readF
        }
    }
  }

  /**
   * Continually read frames from the HTTP2 transport. Demultiplex
   * frames from the transport onto a per-stream receive queue.
   */
  private[this] val demux = {
    def loop(): Future[Unit] =
      if (closed) Future.Unit
      else transport.read().flatMap { frame =>
        frame.streamId match {
          case 0 => log.error(s"Dropping ${frame.name} message on the connection")
          case id => liveStreams.get(id) match {
            case null => log.error(s"Dropping ${frame.name} message on unknown stream ${id}")
            case stream => stream.recvq.offer(frame)
          }
        }
        loop()
      }

    loop().onFailure(log.error(_, "client.dispatch: readLoop"))
  }

  private case class StreamTransport(
    id: Int,
    recvq: AsyncQueue[Http2StreamFrame] = new AsyncQueue
  ) {

    @volatile private[this] var isRequestFinished, isResponseFinished = false

    private[this] def clearIfClosed(): Unit =
      if (isRequestFinished && isResponseFinished) {
        val _ = liveStreams.remove(id, this)
      }

    private[this] def setRequestFinished(): Unit = {
      isRequestFinished = true
      clearIfClosed()
    }

    private[this] def setResponseFinished(): Unit = {
      isResponseFinished = true
      clearIfClosed()
    }

    def writeHeaders(hdrs: Headers, eos: Boolean = false) = {
      val writeF = transport.writeHeaders(hdrs, id, eos)
      if (eos) writeF.ensure(setRequestFinished())
      writeF
    }

    /** Write a request stream */
    def streamRequest(data: DataStream): Future[Unit] = {
      require(!isRequestFinished)
      val write: DataStream.Value => Future[Unit] = { v =>
        val writeF = v match {
          case data: DataStream.Data =>
            transport.writeData(data, id).before(data.release())
          case DataStream.Trailers(tlrs) =>
            transport.writeTrailers(tlrs, id)
        }
        if (v.isEnd) writeF.ensure(setRequestFinished())
        writeF
      }
      def loop(): Future[Unit] = data.read().flatMap(write)
      loop()
    }

    private[this] val write: Http2StreamFrame => Future[Unit] =
      f => transport.write(f)

    def readResponse(): Future[Response] = {
      // Start out by reading response headers from the stream
      // queue. Once a response is initialized, if data is expected,
      // continue reading from the queue until an end stream message is
      // encounetered.
      recvqSizes.add(recvq.size)
      recvq.poll().map {
        case f: Http2HeadersFrame if f.isEndStream =>
          setResponseFinished()
          Response(ResponseHeaders(f.headers), None)

        case f: Http2HeadersFrame =>
          val responseStart = Stopwatch.start()
          val data = new Http2FrameDataStream(recvq, releaser, minAccumFrames, streamStats)
          data.onEnd.onSuccess(_ => responseDurations.add(responseStart().inMillis))
          data.onEnd.ensure(setResponseFinished())
          Response(ResponseHeaders(f.headers), Some(data))

        case f =>
          setResponseFinished()
          throw new IllegalArgumentException(s"Expected response HEADERS; received ${f.name}")
      }
    }

    private[this] val releaser: Int => Future[Unit] =
      bytes => transport.writeWindowUpdate(bytes, id)
  }
}
