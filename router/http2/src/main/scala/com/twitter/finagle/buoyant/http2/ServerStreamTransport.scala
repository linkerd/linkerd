package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.CancelledRequestException
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.io.{Buf, Reader, Writer}
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Promise, Return, Stopwatch, Throw, Time}
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
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Closable {

  // Stream IDs are set by the underling transport

  import ServerStreamTransport._

  private[this] val requestDurations = statsReceiver.stat("request_duration_ms")
  private[this] val responseDurations = statsReceiver.stat("response_duration_ms")

  private[this] val manager: StreamState.Manager =
    new StreamState.Manager(statsReceiver.scope("state"))

  def close(deadline: Time): Future[Unit] =
    transport.close(deadline)

  /** Read the Request from the transport. */
  def read(): Future[Request] =
    transport.read().flatMap { frame =>
      (manager.recv(frame), frame) match {
        case ((StreamState.Idle, StreamState.Idle), _) => read()

        // Server receipt of a complete request without data.
        case (ReadCompleteInit(), frame: Http2HeadersFrame) =>
          Future.value(Request(RequestHeaders(frame.headers)))

        // Server receipt of a streaming request.
        case (ReadStreamInit(rw, trailers), frame: Http2HeadersFrame) =>
          val t = Stopwatch.start()
          val reading = readStream()
          reading.onSuccess(_ => requestDurations.add(t().inMillis))
          Future.value(Request(RequestHeaders(frame.headers), Some(DataStream(rw, trailers))))

        case ((state0, state1), frame) =>
          val e = new IllegalStateException(s"$state0 -> $state1 with frame $frame")
          Future.exception(e)
      }
    }

  /**
   * Read data (and trailer) frames from the transport until an end-of-stream frame is encountered.
   */
  private[this] def readStream(): Future[Unit] = {
    def loop(): Future[Unit] =
      transport.read().flatMap { frame =>
        (manager.recv(frame), frame) match {
          case (ReadStreamActive(writer), frame: Http2DataFrame) =>
            val buf = ByteBufAsBuf.Owned(frame.content.retain())
            writer.write(buf).flatMap(_ => loop())

          case (ReadStreamClosing(writer, trailers), frame: Http2DataFrame) =>
            val wrote = ByteBufAsBuf.Owned(frame.content.retain()) match {
              case Buf.Empty => Future.Unit
              case buf => writer.write(buf)
            }
            wrote.ensure {
              frame.release()
              val closed = writer.close()
              val _ = closed.respond {
                case Throw(e) =>
                  val _ = trailers.updateIfEmpty(Throw(e))
                case Return(_) =>
                  val _ = trailers.updateIfEmpty(Return(None))
              }
            }
            wrote

          case (ReadStreamClosing(writer, trailers), frame: Http2HeadersFrame) =>
            val closed = writer.close()
            closed.respond {
              case Throw(e) =>
                val _ = trailers.updateIfEmpty(Throw(e))
              case Return(_) =>
                val _ = trailers.updateIfEmpty(Return(Some(Headers(frame.headers))))
            }
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
          val t = Stopwatch.start()
          val f = writeStream(reader, trailers)
          f.onSuccess(_ => responseDurations.add(t().inMillis))
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
          val data = new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf))
          transport.write(data).before {
            data.release()
            loop()
          }

        case None =>
          trailers.flatMap {
            case None =>
              val data = new DefaultHttp2DataFrame(true /* eos */ )
              transport.write(data)

            case Some(trailers) =>
              val frame = new DefaultHttp2HeadersFrame(getHeaders(trailers), true /* eos */ )
              transport.write(frame)
          }
      }

    loop()
  }

}
