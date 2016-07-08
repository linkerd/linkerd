package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.CancelledRequestException
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.io.{Reader, Writer}
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Time}
import io.netty.handler.codec.http2._
import scala.collection.JavaConverters._

class ServerStreamTransport(transport: Transport[Http2StreamFrame, Http2StreamFrame]) {

  private[this] val log = Logger.get(getClass.getName)

  private[this] val manager = new StreamState.Manager

  def close(deadline: Time): Future[Unit] =
    transport.close(deadline)

  def read(): Future[Request] =
    transport.read().flatMap { frame =>
      val (s0, s1) = manager.recv(frame)

      (s0, s1, frame) match {
        case (StreamState.Idle, StreamState.Idle, _) => read()

        case (StreamState.Idle, StreamState.RemoteOpen(Some(StreamState.Recv(rw, trailers))), frame: Http2HeadersFrame) =>
          log.info(s"srv.dispatch: ${transport.remoteAddress} read ${frame}")
          trailers.become(readStream(rw))
          Future.value(Request(RequestHeaders(frame.headers), Some(DataStream(rw, trailers))))

        case (StreamState.Idle, StreamState.HalfClosedRemote(_), frame: Http2HeadersFrame) =>
          log.info(s"srv.dispatch: ${transport.remoteAddress} read ${frame}")
          Future.value(Request(RequestHeaders(frame.headers)))

        case (state0, state1, frame) =>
          val e = new IllegalStateException(s"$state0 -> $state1 with frame $frame")
          Future.exception(e)
      }
    }

  private[this] def readStream(writer: Writer with Closable): Future[Option[Headers]] = {
    def loop(): Future[Option[Headers]] =
      transport.read().flatMap { frame =>
        val (s0, s1) = manager.recv(frame)

        (s0, s1, frame) match {
          case (StreamState.RemoteOpen(_), StreamState.RemoteOpen(_), frame: Http2DataFrame) =>
            val buf = ByteBufAsBuf.Owned(frame.content) // YOLO
            writer.write(buf).flatMap { _ => loop() }

          case (StreamState.RemoteOpen(_), StreamState.RemoteOpen(_), _) =>
            loop()

          case (StreamState.RemoteOpen(_), StreamState.RemoteClosed(), frame: Http2DataFrame) =>
            val buf = ByteBufAsBuf.Owned(frame.content)
            writer.write(buf).flatMap { _ => writer.close() }.map { _ => None }

          case (StreamState.RemoteOpen(_), StreamState.RemoteClosed(), frame: Http2HeadersFrame) =>
            writer.close().map { _ => Some(Headers(frame.headers)) }

          case (state0, state1, frame) =>
            val e = new IllegalStateException(s"$state0 -> $state1 with frame $frame")
            Future.exception(e)
        }
      }

    loop()
  }

  def write(rsp: Response): Future[Future[Unit]] =
    manager.send(rsp) match {
      case (StreamState.LocalOpen(None), StreamState.LocalOpen(Some(StreamState.Send(reader, trailers)))) =>
        val hframe = new DefaultHttp2HeadersFrame(headers(rsp.headers), false /* eos */ )
        transport.write(hframe).map { _ =>
          writeStream(reader, trailers)
        }

      case (StreamState.LocalOpen(None), StreamState.LocalClosed()) =>
        val hframe = new DefaultHttp2HeadersFrame(headers(rsp.headers), true /* eos */ )
        transport.write(hframe).map(_ => Future.Unit)

      case (state0, state1) =>
        val e = new IllegalStateException(s"$state0 -> $state1 writing response")
        Future.exception(e)
    }

  private[this] def headers(hs: Headers): Http2Headers = hs match {
    case hs: Netty4Headers => hs.underlying
    case hs =>
      val headers = new DefaultHttp2Headers
      for ((k, v) <- hs.toSeq) headers.add(k, v)
      headers
  }

  private[this] val BufSize = Int.MaxValue // FIXME

  private[this] def writeStream(reader: Reader, trailers: Future[Option[Headers]]): Future[Unit] = {
    def loop(): Future[Unit] =
      reader.read(BufSize).flatMap {
        case Some(buf) =>
          val data = new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf)) // YOLO?
          transport.write(data).flatMap { _ => loop() }

        case None =>
          trailers.flatMap {
            case None =>
              val data = new DefaultHttp2DataFrame(true /* eos */ )
              transport.write(data)

            case Some(trailers) =>
              val data = new DefaultHttp2HeadersFrame(headers(trailers), true /* eos */ )
              transport.write(data)
          }
      }

    loop()
  }

}
