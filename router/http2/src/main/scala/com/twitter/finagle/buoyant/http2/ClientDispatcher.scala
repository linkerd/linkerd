package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Service
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.io.{Buf, Reader, Writer}
import com.twitter.util.{Closable, Future, Promise, Return, Throw}
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ClientDispatcher {
  private val log = Logger.get(getClass.getName)

  private val MaxStreamId = math.pow(2, 31) - 1

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

  private def mkDataFrameEos(streamId: Int): Http2DataFrame =
    new DefaultHttp2DataFrame(true /*eos*/ ).setStreamId(streamId)

  private def mkDataFrame(streamId: Int, buf: Buf, eos: Boolean = false): Http2DataFrame = {
    val bb = BufAsByteBuf.Owned(buf).retain()
    new DefaultHttp2DataFrame(bb, eos).setStreamId(streamId)
  }

  private case class Stream(
    id: Int,
    manager: StreamState.Manager = new StreamState.Manager,
    response: Promise[Response] = Promise[Response]
  )

  /*
   * Helpers for matching stream state transitions.
   */

  private object ActiveResponseInit {
    def unapply(
      states: (StreamState, StreamState)
    ): Option[(Reader with Writer with Closable, Promise[Option[Headers]])] =
      states match {
        case (StreamState.RemoteIdle(), StreamState.RemoteActive(rw, t)) => Some((rw, t))
        case _ => None
      }
  }

  private object ActiveResponse {
    def unapply(states: (StreamState, StreamState)): Option[Reader with Writer with Closable] =
      states match {
        case (StreamState.RemoteActive(_, _), StreamState.RemoteActive(rw, _)) => Some(rw)
        case _ => None
      }
  }

  private object ActiveResponseEnd {
    def unapply(
      states: (StreamState, StreamState)
    ): Option[(Reader with Writer with Closable, Promise[Option[Headers]])] =
      states match {
        case (StreamState.RemoteActive(rw, t), StreamState.RemoteClosed()) => Some((rw, t))
        case _ => None
      }
  }

  private object FullResponse {
    def unapply(states: (StreamState, StreamState)): Boolean = states match {
      case (StreamState.RemoteIdle(), StreamState.RemoteClosed()) => true
      case _ => false
    }
  }
}

/**
 * Multiplexes HTTP/2 request/responses onto HTTP/2 streams over a
 * shared connection transport.
 */
class ClientDispatcher(transport: Transport[Http2StreamFrame, Http2StreamFrame])
  extends Service[Request, Response] {

  import ClientDispatcher._

  // TODO handle overflow
  private[this] val _id = new AtomicInteger(3) // ID=1 is reserved for HTTP/1 upgrade
  private[this] def nextId() = _id.getAndAdd(2)
  private[this] val streams = new ConcurrentHashMap[Int, Stream]

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
        val frame = mkHeadersFrame(stream.id, req.headers, true /*eos*/ )
        transport.write(frame).before(stream.response)

      // New request, with data to be sent.
      case (StreamState.Idle, StreamState.LocalActive(reader, trailers)) =>
        val frame = mkHeadersFrame(stream.id, req.headers, false /*eos*/ )
        transport.write(frame).before {
          // Read data from the local (request) `reader`, writing it
          // on the http2 transport as data frames. These writes are
          // subject to flow control and therefore may be delayed.
          val writing = writeLoop(stream.id, reader, trailers)
          writing.onFailure { e =>
            log.error(e, s"client.dispatch: ${stream.id} writing error")
            // If writing fails before a response is satisified, fail
            // the response and cancel any pending work.
            val _ = stream.response.updateIfEmpty(Throw(e))
            stream.response.raise(e)
          }
          writing.respond { _ => clearIfClosed(stream) }

          // If the response fails, try to stop writing data.
          stream.response.onFailure(writing.raise(_))
          stream.response
        }

      // Unexpected state
      case (s0, s1) =>
        clearIfClosed(s1, stream)
        val e = new IllegalStateException(s"stream ${stream.id} $s0 -> $s1")
        Future.exception(e)
    }
  }

  private[this] def clearIfClosed(state: StreamState, stream: Stream): Unit =
    stream.manager.state match {
      case StreamState.Closed | StreamState.Invalid(_, _) =>
        val _ = streams.remove(state, stream)
      case _ =>
    }

  private[this] def clearIfClosed(stream: Stream): Unit =
    clearIfClosed(stream.manager.state, stream)

  private[this] val reading = {
    def loop(): Future[Unit] =
      transport.read().flatMap { frame =>
        streams.get(frame.streamId) match {
          case null =>
            log.error(s"no stream id on $frame")
            loop()

          case stream@Stream(_, manager, response) =>
            (manager.recv(frame), frame) match {

              /*
               * Response headers
               */

              case (FullResponse(), f: Http2HeadersFrame) =>
                clearIfClosed(stream)
                val rsp = Response(ResponseHeaders(f.headers), None)
                response.setValue(rsp)
                loop()

              case (ActiveResponseInit(reader, trailers), f: Http2HeadersFrame) =>
                val rsp = Response(ResponseHeaders(f.headers), Some(DataStream(reader, trailers)))
                response.setValue(rsp)
                loop()

              /*
               * Data
               */

              case (ActiveResponse(rw), f: Http2DataFrame) =>
                val sz = f.content.readableBytes + f.padding
                rw.write(ByteBufAsBuf.Owned(f.content)).before {
                  // TODO f.content.release()
                  updateCapacity(stream, sz).before {
                    loop()
                  }
                }

              case (ActiveResponseEnd(rw, trailers), f: Http2DataFrame) =>
                clearIfClosed(stream)
                rw.write(ByteBufAsBuf.Owned(f.content)).before(rw.close()).transform {
                  case t@Throw(e) =>
                    trailers.updateIfEmpty(Throw(e))
                    Future.const(t)

                  case Return(_) =>
                    trailers.updateIfEmpty(Return(None))
                    loop()
                }

              /*
               * Trailers
               */

              case (ActiveResponseEnd(rw, trailers), f: Http2HeadersFrame) =>
                clearIfClosed(stream)
                rw.close().before {
                  trailers.setValue(Some(Headers(f.headers)))
                  loop()
                }

              case ((s0, s1), f) =>
                clearIfClosed(s1, stream)
                Future.exception(new IllegalStateException(s"[${f.name} ${f.streamId}] $s0 -> $s1"))
            }
        }
      }

    loop().onFailure { e =>
      log.error(e, "client.dispatch: readLoop")
    }
  }

  private[this] def updateCapacity(stream: Stream, bytes: Int): Future[Unit] = {
    val frame = new DefaultHttp2WindowUpdateFrame(bytes).setStreamId(stream.id)
    transport.write(frame)
  }

  private[this] def writeLoop(streamId: Int, reader: Reader, trailers: Future[Option[Headers]]): Future[Unit] = {
    def loop(): Future[Unit] = {
      reader.read(Int.MaxValue).flatMap {
        case None =>
          trailers.flatMap {
            case None =>
              transport.write(mkDataFrameEos(streamId))

            case Some(trailers) =>
              transport.write(mkHeadersFrame(streamId, trailers, true /*eos*/ ))
          }

        case Some(buf) =>
          val frame = mkDataFrame(streamId, buf, eos = false)
          transport.write(frame).before(loop())
      }
    }

    loop()
  }

}
