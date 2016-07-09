package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Service
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.io.Reader
import com.twitter.util.{Future, Promise, Return, Throw}
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ClientDispatcher(
  transport: Transport[Http2StreamFrame, Http2StreamFrame]
) extends Service[Request, Response] {

  private[this] val log = Logger.get(getClass.getName)

  // TODO handle overflow
  private[this] val _id = new AtomicInteger(1)
  private[this] def nextId() = _id.getAndAdd(2)

  case class Stream(manager: StreamState.Manager, response: Promise[Response])
  private[this] val streams = new ConcurrentHashMap[Int, Stream]

  def apply(req: Request): Future[Response] = {
    val streamId = nextId()
    val state = new StreamState.Manager
    val rspp = Promise[Response]
    streams.putIfAbsent(streamId, Stream(state, rspp)) match {
      case null => // expected
      case state =>
        throw new IllegalStateException(s"stream $streamId already exists as $state")
    }

    val frame = headers(req.headers, req.data.isEmpty).setStreamId(streamId)
    state.send(req) match {
      case (StreamState.Idle, StreamState.Open(_, Some(StreamState.Send(reader, trailers)))) =>
        // New request, with data to be sent...

        log.info(s"client.dispatch: $streamId writing $frame")
        transport.write(frame).before {
          log.info(s"client.dispatch: $streamId writing data...")
          // TODO interrupts
          val writing = writeLoop(streamId, reader, trailers)
          val _ = writing.onFailure { e =>
            log.error(e, s"client.dispatch: $streamId writing")
          }

          rspp
        }

      case (StreamState.Idle, StreamState.RemoteOpen(_)) =>
        log.info(s"client.dispatch: $streamId writing $frame")
        // New request, without data to send
        transport.write(frame).before(rspp)

      case (s0, s1) => Future.exception(new IllegalStateException(s"stream $streamId $s0 -> $s1"))
    }
  }

  private[this] def headers(orig: Headers, eos: Boolean): Http2HeadersFrame = {
    val headers = orig match {
      case h: Netty4Headers => h.underlying
      case hs =>
        val headers = new DefaultHttp2Headers
        for ((k, v) <- hs.toSeq) headers.add(k, v)
        headers
    }
    new DefaultHttp2HeadersFrame(headers, eos)
  }

  private[this] val reading = {
    def loop(): Future[Unit] = {
      log.info(s"client.dispatch: readLoop reading")
      transport.read().flatMap { frame =>
        log.info(s"client.dispatch: readLoop read $frame")
        streams.get(frame.streamId) match {
          case null =>
            log.error(s"no stream id on $frame")
            loop()

          case Stream(manager, response) =>
            val (s0, s1) = manager.recv(frame)
            log.info(s"client.dispatch: readLoop init state $s0")
            log.info(s"client.dispatch: readLoop next state $s1")
            (s0, s1, frame) match {

              /*
               * Response headers
               */

              case (StreamState.RemoteOpen(None), StreamState.RemoteClosed(), f: Http2HeadersFrame) =>
                val rsp = Response(ResponseHeaders(f.headers), None)
                response.setValue(rsp)
                loop()

              case (StreamState.RemoteOpen(None), StreamState.RemoteOpen(Some(StreamState.Recv(reader, trailers))), f: Http2HeadersFrame) =>
                val rsp = Response(ResponseHeaders(f.headers), Some(DataStream(reader, trailers)))
                response.setValue(rsp)
                loop()

              /*
               * Data
               */

              case (StreamState.RemoteOpen(Some(StreamState.Recv(rw, trailers))), StreamState.RemoteClosed(), f: Http2DataFrame) =>
                val buf = ByteBufAsBuf.Owned(f.content.retainedDuplicate())
                rw.write(buf).before(rw.close()).before {
                  trailers.setValue(None)
                  loop()
                }

              case (StreamState.RemoteOpen(Some(StreamState.Recv(rw, _))), StreamState.RemoteOpen(Some(_)), f: Http2DataFrame) =>
                val buf = ByteBufAsBuf.Owned(f.content.duplicate().retain())
                rw.write(buf).before {
                  loop()
                }

              /*
               * Trailers
               */

              case (StreamState.RemoteOpen(Some(StreamState.Recv(rw, trailers))), StreamState.RemoteClosed(), f: Http2HeadersFrame) =>
                rw.close().before {
                  trailers.setValue(Some(Headers(f.headers)))
                  loop()
                }

              case (s0, s1, f) => Future.exception(new IllegalStateException(s"[${f.name} ${f.streamId}] $s0 -> $s1"))
            }
        }
      }
    }

    loop().onFailure { e =>
      log.error(e, "client.dispatch: readLoop")
    }
  }

  private[this] def writeLoop(streamId: Int, reader: Reader, trailers: Future[Option[Headers]]): Future[Unit] = {
    def loop(): Future[Unit] = {
      log.info(s"client.dispatch: $streamId writeLoop reading")
      reader.read(Int.MaxValue).flatMap {
        case None =>
          log.info(s"client.dispatch: $streamId writeLoop eos...")
          trailers.flatMap {
            case None =>
              log.info(s"client.dispatch: $streamId writeLoop eos data 0B")
              val frame = new DefaultHttp2DataFrame(true /*eos*/ ).setStreamId(streamId)
              transport.write(frame)

            case Some(trailers) =>
              log.info(s"client.dispatch: $streamId writeLoop eos trailers $trailers")
              val frame = headers(trailers, true /*eos*/ ).setStreamId(streamId)
              transport.write(frame)
          }

        case Some(buf) =>
          log.info(s"client.dispatch: $streamId writeLoop read data ${buf.length}B")
          val bb = try BufAsByteBuf.Owned(buf).retain() catch {
            case e: Throwable =>
              log.error(e, "client.dispatch: retain")
              throw e
          }
          val frame = new DefaultHttp2DataFrame(bb, false /*eos*/ ).setStreamId(streamId)
          log.info(s"client.dispatch: $streamId writeLoop writing $frame")
          transport.write(frame).before {
            log.info(s"client.dispatch: $streamId writeLoop wrote $frame")
            loop()
          }
      }
    }

    loop()
  }

}
