package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.CancelledRequestException
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.io.{Reader, Writer}
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Promise, Time}
import io.netty.handler.codec.http2._

object ServerStreamTransport {

  private val log = Logger.get(getClass.getName)

  private val BufSize = Int.MaxValue // YOLO?

  private object ReadCompleteInit {
    def unapply(states: (StreamState, StreamState)): Boolean = states match {
      case (StreamState.Idle, StreamState.HalfClosedRemote(_)) => true
      case _ => false
    }
  }

  private object ReadStreamInit {
    def unapply(
      states: (StreamState, StreamState)
    ): Option[(Reader with Writer with Closable, Promise[Option[Headers]])] =
      states match {
        case (StreamState.Idle, StreamState.RemoteActive(rw, trailers)) => Some((rw, trailers))
        case _ => None
      }
  }

  private object ReadStreamActive {
    def unapply(states: (StreamState, StreamState)): Option[Reader with Writer with Closable] =
      states match {
        case (StreamState.RemoteActive(writer, _), StreamState.RemoteActive(_, _)) => Some(writer)
        case _ => None
      }
  }

  private object ReadStreamClosing {
    def unapply(
      states: (StreamState, StreamState)
    ): Option[(Reader with Writer with Closable, Promise[Option[Headers]])] =
      states match {
        case (StreamState.RemoteActive(rw, t), StreamState.RemoteClosed()) => Some((rw, t))
        case _ => None
      }
  }

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
  manager: StreamState.Manager = new StreamState.Manager
) extends Closable {

  // Stream IDs are set by the underling transport

  import ServerStreamTransport._

  def close(deadline: Time): Future[Unit] =
    transport.close(deadline)

  def read(): Future[Request] =
    transport.read().flatMap { frame =>
      (manager.recv(frame), frame) match {
        case ((StreamState.Idle, StreamState.Idle), _) => read()

        // Server receipt of a complete request without data.
        case (ReadCompleteInit(), frame: Http2HeadersFrame) =>
          Future.value(Request(RequestHeaders(frame.headers)))

        // Server receipt of a streaming request.
        case (ReadStreamInit(rw, trailers), frame: Http2HeadersFrame) =>
          val reading = readStream()
          Future.value(Request(RequestHeaders(frame.headers), Some(DataStream(rw, trailers))))

        case ((state0, state1), frame) =>
          val e = new IllegalStateException(s"$state0 -> $state1 with frame $frame")
          Future.exception(e)
      }
    }

  private[this] def readStream(): Future[Unit] = {
    def loop(): Future[Unit] =
      transport.read().flatMap { frame =>
        (manager.recv(frame), frame) match {
          case (ReadStreamActive(writer), frame: Http2DataFrame) =>
            val buf = ByteBufAsBuf.Owned(frame.content.retain())
            writer.write(buf).flatMap(_ => loop())

          case (ReadStreamClosing(writer, trailers), frame: Http2DataFrame) =>
            val buf = ByteBufAsBuf.Owned(frame.content.retain())
            val wrote = writer.write(buf).before(writer.close())
            wrote.map(_ => None).proxyTo(trailers)
            wrote

          case (ReadStreamClosing(writer, trailers), frame: Http2HeadersFrame) =>
            val closed = writer.close()
            trailers.become(closed.map(_ => Some(Headers(frame.headers))))
            closed

          case ((s0, s1), f) =>
            val e = new IllegalStateException(s"[${f.name} ${f.streamId}] $s0 -> $s1")
            Future.exception(e)
        }
      }

    loop()
  }

  def write(rsp: Response): Future[Future[Unit]] =
    manager.send(rsp) match {
      case (StreamState.LocalIdle(), StreamState.LocalActive(reader, trailers)) =>
        val hframe = new DefaultHttp2HeadersFrame(getHeaders(rsp.headers), false /* eos */ )
        transport.write(hframe).map { _ =>
          writeStream(reader, trailers)
        }

      case (StreamState.LocalIdle(), StreamState.LocalClosed()) =>
        val hframe = new DefaultHttp2HeadersFrame(getHeaders(rsp.headers), true /* eos */ )
        transport.write(hframe).map(_ => Future.Unit)

      case (state0, state1) =>
        val e = new IllegalStateException(s"$state0 -> $state1 writing response")
        Future.exception(e)
    }

  private[this] def writeStream(reader: Reader, trailers: Future[Option[Headers]]): Future[Unit] = {
    def loop(): Future[Unit] =
      reader.read(BufSize).flatMap {
        case Some(buf) =>
          val bb = BufAsByteBuf.Owned(buf).retain() // YOLO?
          val data = new DefaultHttp2DataFrame(bb)
          transport.write(data).before(loop())

        case None =>
          trailers.flatMap {
            case None =>
              val data = new DefaultHttp2DataFrame(true /* eos */ )
              transport.write(data)

            case Some(trailers) =>
              val data = new DefaultHttp2HeadersFrame(getHeaders(trailers), true /* eos */ )
              transport.write(data)
          }
      }

    loop()
  }

}
