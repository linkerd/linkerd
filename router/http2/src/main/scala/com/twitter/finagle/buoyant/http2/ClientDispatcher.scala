package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Service
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.io.{Buf, Reader, Writer}
import com.twitter.util.{Closable, Future, Promise, Return, Stopwatch, Throw}
import io.netty.buffer.ByteBufAllocator
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ClientDispatcher {
  private val log = Logger.get(getClass.getName)

  private val MaxStreamId = math.pow(2, 31) - 1

  type StateTransition = (StreamState, StreamState)
  type Inbound = Reader with Writer with Closable
  type TrailerPromise = Promise[Option[Headers]]
  type TrailerFuture = Future[Option[Headers]]

  private def mkHeadersFrame(
    streamId: Int,
    orig: Headers,
    eos: Boolean
  ): Http2HeadersFrame = {
    val headers = orig match {
      case h: Netty4Headers => h.underlying
      case hs =>
        val headers = new DefaultHttp2Headers
        for ((k, v) <- hs.toSeq) headers.add(k, v)
        headers
    }
    new DefaultHttp2HeadersFrame(headers, eos).setStreamId(streamId)
  }

  /*
   * Helpers for matching stream state transitions.
   */

  private object ActiveResponseInit {
    def unapply(states: StateTransition): Option[(Inbound, TrailerPromise)] =
      states match {
        case (StreamState.RemoteIdle(), StreamState.RemoteActive(rw, t)) => Some((rw, t))
        case _ => None
      }
  }

  private object ActiveResponse {
    def unapply(states: StateTransition): Option[Inbound] =
      states match {
        case (StreamState.RemoteActive(_, _), StreamState.RemoteActive(rw, _)) => Some(rw)
        case _ => None
      }
  }

  private object ActiveResponseEnd {
    def unapply(states: StateTransition): Option[(Inbound, TrailerPromise)] =
      states match {
        case (StreamState.RemoteActive(rw, t), StreamState.RemoteClosed()) => Some((rw, t))
        case _ => None
      }
  }

  private object FullResponse {
    def unapply(states: StateTransition): Boolean = states match {
      case (StreamState.RemoteIdle(), StreamState.RemoteClosed()) => true
      case _ => false
    }
  }
}

/**
 * Multiplexes HTTP/2 request/responses onto HTTP/2 streams over a
 * shared connection transport.
 */
class ClientDispatcher(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
  allocator: ByteBufAllocator,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Service[Request, Response] {

  import ClientDispatcher._

  // TODO handle overflow
  private[this] val _id = new AtomicInteger(3) // ID=1 is reserved for HTTP/1 upgrade
  private[this] def nextId() = _id.getAndAdd(2)
  private[this] val streams = new ConcurrentHashMap[Int, Stream]

  private[this] val streamStats = statsReceiver.scope("streams")
  private[this] val activeStreams = streamStats.addGauge("live") { streams.size }
  private[this] val streamStateStats = streamStats.scope("state")

  private[this] val transportStats = statsReceiver.scope("transport")
  private[this] val transportReadTimes = transportStats.stat("read_us")
  private[this] val transportWriteTimes = transportStats.stat("write_us")

  private[this] val enqueueTimes = streamStats.stat("enqueue_us")
  private[this] val dequeueTimes = streamStats.stat("dequeue_us")
  private[this] val queueLengths = streamStats.stat("qlen")

  private[this] val requestDurations = statsReceiver.stat("request_duration_ms")
  private[this] val responseDurations = statsReceiver.stat("response_duration_ms")

  /**
   * Write a request on the underlying connection and return its
   * response when it is received.
   */
  def apply(req: Request): Future[Response] = {
    // Initialize a new Stream; and store it so that a response may be
    // demultiplexed.
    val stream = Stream(nextId())
    streams.putIfAbsent(stream.id, stream) match {
      case null => // expected
      case state =>
        val e = new IllegalStateException(s"stream ${stream.id} already exists as $state")
        return Future.exception(e)
    }

    stream.manager.send(req) match {
      // New request, sans data.
      case (StreamState.Idle, StreamState.LocalClosed()) =>
        stream.writeHeaders(req.headers, eos = true).before(stream.read())

      // New request, with data to be sent.
      case (StreamState.Idle, StreamState.LocalActive(reader, trailers)) =>
        stream.writeHeaders(req.headers, false /*eos*/ ).before {
          // Read data from the local (request) `reader`, writing it
          // on the http2 transport as data frames. These writes are
          // subject to flow control and therefore may be delayed.
          val clock = Stopwatch.start()
          val writing = stream.writeFrom(reader, trailers)
          writing.onSuccess(_ => requestDurations.add(clock().inMillis))

          val rsp = stream.read()
          writing.onFailure { e =>
            log.error(e, s"client.dispatch: ${stream.id} writing error")
            rsp.raise(e)
            trailers.raise(e)
          }
          writing.ensure(clearIfClosed(stream))
          // If the response fails, try to stop writing data.
          rsp.onFailure(writing.raise(_))

          rsp
        }

      // Unexpected state
      case (s0, s1) =>
        clearIfClosed(s1, stream)
        val e = new IllegalStateException(s"stream ${stream.id} $s0 -> $s1")
        Future.exception(e)
    }
  }

  private[this] def clearIfClosed(state: StreamState, stream: Stream): Unit =
    state match {
      case StreamState.Closed | StreamState.Invalid(_, _) =>
        val _ = streams.remove(stream.id, stream)
      case _ =>
    }

  private[this] def clearIfClosing(states: StateTransition, stream: Stream): Unit = {
    val (_, state) = states
    clearIfClosed(state, stream)
  }

  private[this] def clearIfClosed(stream: Stream): Unit =
    clearIfClosed(stream.manager.state, stream)

  private[this] val reading = {
    def loop(): Future[Unit] = {
      val readSnap = Stopwatch.start()
      transport.read().flatMap { frame =>
        transportReadTimes.add(readSnap().inMicroseconds)
        frame.streamId match {
          case 0 => log.error(s"received ${frame.name} message on the connection")
          case id =>
            streams.get(id) match {
              case null => log.error(s"received ${frame.name} message on unknown stream ${id}")
              case stream =>
                val qSnap = Stopwatch.start()
                stream.readq.offer(frame)
                enqueueTimes.add(qSnap().inMicroseconds)
            }
        }
        loop()
      }
    }

    loop().onFailure { e =>
      log.error(e, "client.dispatch: readLoop")
    }
  }

  private case class Stream(
    id: Int,
    readq: AsyncQueue[Http2StreamFrame] = new AsyncQueue,
    manager: StreamState.Manager = new StreamState.Manager(streamStateStats)
  ) {

    def read(): Future[Response] = {
      // Start out by reading response headers from the stream
      // queue. Once a response is initialized, if data is expected,
      // continue reading from the queue until an end stream message is
      // encounetered.
      val snap = Stopwatch.start()
      readq.poll().onSuccess { _ =>
        dequeueTimes.add(snap().inMicroseconds)
      }.flatMap {
        case frame: Http2HeadersFrame =>
          manager.recv(frame) match {
            case s@FullResponse() =>
              clearIfClosing(s, this)
              val rsp = Response(ResponseHeaders(frame.headers), None)
              Future.value(rsp)

            case ActiveResponseInit(rw, trailers) =>
              val clock = Stopwatch.start()
              val wrote = readTo(rw)
              wrote.onSuccess(_ => responseDurations.add(clock().inMillis))
              trailers.become(wrote)
              val rsp = Response(ResponseHeaders(frame.headers), Some(DataStream(rw, trailers)))
              Future.value(rsp)

            case (s0, s1) =>
              clearIfClosed(s1, this)
              val e = new IllegalStateException(s"Invalid response state: $s0 -> $s1")
              Future.exception(e)
          }

        case frame =>
          val s = manager.recv(frame)
          clearIfClosing(s, this)
          val e = new IllegalArgumentException(s"Expected response HEADERS; received ${frame.name}")
          Future.exception(e)
      }
    }

    def readTo(rw: Inbound): Future[Option[Headers]] = {
      def loop(): Future[Option[Headers]] = {
        val snap = Stopwatch.start()
        queueLengths.add(readq.size)
        if (readq.size > 2) {
          // Pull everything available out of the queue so that we can
          // aggregate data frames rather than process each write serially.
          readq.drain() match {
            case Throw(e) => Future.exception(e)
            // Unexpected, but handle the case where we got 0 frames
            case Return(frames) if frames.isEmpty => loop()
            case Return(frames) =>
              dequeueTimes.add(snap().inMicroseconds)

              val agg = allocator.compositeBuffer(frames.length)
              // var agg = Buf.Empty

              // handrolled loops for great victory
              var i = 0
              var headers: Headers = null
              var eos = false
              var reclaimed = 0
              while (i != frames.length && headers == null) {
                frames(i) match {
                  case frame: Http2DataFrame =>
                    val _ = manager.recv(frame)
                    eos = frame.isEndStream
                    if (frame.content.readableBytes > 0) {
                      reclaimed += frame.content.readableBytes + frame.padding

                      agg.addComponent(frame.content.copy())
                      // agg = agg.concat(ByteBufAsBuf.Owned(frame.content.copy())) // XXX wtf
                    }
                    i += 1

                  case frame: Http2HeadersFrame if frame.isEndStream =>
                    val _ = manager.recv(frame)
                    headers = Headers(frame.headers)
                    eos = true

                  case frame =>
                    val _ = manager.recv(frame)
                    i += 1
                }
              }

              rw.write(ByteBufAsBuf.Owned(agg)).before {
                updateCapacity(reclaimed).before {
                  if (eos) {
                    rw.close().map { _ =>
                      if (headers == null) None
                      else Some(headers)
                    }
                  } else loop()
                }
              }
          }
        } else {
          readq.poll().flatMap { frame =>
            dequeueTimes.add(snap().inMicroseconds)
            (manager.recv(frame), frame) match {

              /*
               * Data
               */

              case (ActiveResponse(_), f: Http2DataFrame) =>
                // Retain the data since we're writing it as an owned
                // buffer on the writer. The reader is responsible for
                // releasing buffers when they are unused.
                ByteBufAsBuf.Owned(f.content.retain()) match {
                  case Buf.Empty => loop()
                  case buf =>
                    val sz = f.content.readableBytes + f.padding
                    rw.write(buf).before {
                      updateCapacity(sz).before {
                        // TODO: reading should be able to continue
                        // immediately (before the write completes, but
                        // this causes odd behavior...
                        loop()
                      }
                    }
                }

              case (s@ActiveResponseEnd(_, _), f: Http2DataFrame) =>
                // Retain the data since we're writing it as an owned
                // buffer on the writer. The reader is responsible for
                // releasing buffers when they are unused.
                val wrote = ByteBufAsBuf.Owned(f.content.retain()) match {
                  case Buf.Empty => Future.Unit
                  case buf => rw.write(buf)
                }
                wrote.before(rw.close()).transform {
                  case Throw(e) =>
                    clearIfClosing(s, this)
                    Future.exception(e)

                  case Return(_) =>
                    clearIfClosing(s, this)
                    Future.value(None)
                }

              /*
               * Trailers
               */

              case (s@ActiveResponseEnd(_, _), f: Http2HeadersFrame) =>
                clearIfClosing(s, this)
                rw.close().map(_ => Some(Headers(f.headers)))

              case ((s0, s1), f) =>
                clearIfClosed(s1, this)
                Future.exception(new IllegalStateException(s"[${f.name} ${id}] $s0 -> $s1"))
            }
          }
        }

      }

      loop()
    }

    /** Write a request stream */
    def writeFrom(reader: Reader, trailers: TrailerFuture): Future[Unit] = {
      def loop(): Future[Unit] =
        reader.read(Int.MaxValue).flatMap {
          case Some(buf) if !buf.isEmpty =>
            writeData(buf, eos = false).before(loop())

          case _ =>
            trailers.flatMap {
              case None => endStream()
              case Some(trailers) => writeHeaders(trailers, eos = true)
            }
        }

      loop()
    }

    private[this] def write(f: Http2StreamFrame): Future[Unit] = {
      val snap = Stopwatch.start()
      transport.write(f).onSuccess { _ =>
        transportWriteTimes.add(snap().inMicroseconds)
      }
    }

    def updateCapacity(bytes: Int): Future[Unit] =
      write(new DefaultHttp2WindowUpdateFrame(bytes).setStreamId(id))

    def endStream(): Future[Unit] =
      write(new DefaultHttp2DataFrame(true /*eos*/ ).setStreamId(id))

    def writeData(buf: Buf, eos: Boolean = false): Future[Unit] = {
      val bb = BufAsByteBuf.Owned(buf).retain()
      write(new DefaultHttp2DataFrame(bb, eos).setStreamId(id)).ensure {
        val _ = bb.release()
      }
    }

    def writeHeaders(headers: Headers, eos: Boolean = false): Future[Unit] =
      write(mkHeadersFrame(id, headers, eos))
  }
}
