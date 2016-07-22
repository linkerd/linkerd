package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
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

  // private val BufSize = Int.MaxValue // YOLO?

  // private object ReadCompleteInit {
  //   def unapply(states: (StreamState, StreamState)): Boolean = states match {
  //     case (StreamState.Idle, StreamState.HalfClosedRemote(_)) => true
  //     case _ => false
  //   }
  // }

  // private object ReadStreamInit {
  //   def unapply(
  //     states: (StreamState, StreamState)
  //   ): Option[(Reader with Writer with Closable, Promise[Option[Headers]])] =
  //     states match {
  //       case (StreamState.Idle, StreamState.RemoteActive(rw, trailers)) => Some((rw, trailers))
  //       case _ => None
  //     }
  // }

  // private object ReadStreamActive {
  //   def unapply(states: (StreamState, StreamState)): Option[Reader with Writer with Closable] =
  //     states match {
  //       case (StreamState.RemoteActive(writer, _), StreamState.RemoteActive(_, _)) => Some(writer)
  //       case _ => None
  //     }
  // }

  // private object ReadStreamClosing {
  //   def unapply(
  //     states: (StreamState, StreamState)
  //   ): Option[(Reader with Writer with Closable, Promise[Option[Headers]])] =
  //     states match {
  //       case (StreamState.RemoteActive(rw, t), StreamState.RemoteClosed()) => Some((rw, t))
  //       case _ => None
  //     }
  // }

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

  // private[this] val manager: StreamState.Manager =
  //   new StreamState.Manager(statsReceiver.scope("state"))

  def close(deadline: Time): Future[Unit] =
    transport.close(deadline)

  /** Read the Request from the transport. */
  def read(): Future[Request] =
    transport.read().flatMap {
      case f: Http2HeadersFrame if f.isEndStream =>
        Future.value(Request(RequestHeaders(f.headers)))

      case f: Http2HeadersFrame =>
        val t = Stopwatch.start()
        val recvq = new AsyncQueue[DataStream.Value]
        val reading = readStream(recvq)
        reading.onSuccess(_ => requestDurations.add(t().inMillis))
        Future.value(Request(RequestHeaders(f.headers), Some(new AQDataStream(recvq))))

      case f =>
        val e = new IllegalStateException(s"Read unexpected ${f.name}; expected HEADERS")
        Future.exception(e)
    }

  /**
   * Read data (and trailer) frames from the transport until an end-of-stream frame is encountered.
   */
  private[this] def readStream(recvq: AsyncQueue[DataStream.Value]): Future[Unit] = {
    def loop(): Future[Unit] =
      transport.read().flatMap {
        case f: Http2DataFrame =>
          val buf = ByteBufAsBuf.Owned(f.content.retain())
          val release: () => Future[Unit] = () => {
            val _ = f.release()
            Future.Unit
          }
          recvq.offer(DataStream.Data(buf, f.isEndStream, release))
          if (f.isEndStream) Future.Unit else loop()

        case f: Http2HeadersFrame if f.isEndStream =>
          recvq.offer(DataStream.Trailers(Headers(f.headers)))
          Future.Unit

        case f =>
          val e = new IllegalStateException(s"Read unexpected ${f.name}; expected DATA or HEADERS")
          Future.exception(e)
      }

    loop()
  }

  def write(rsp: Response): Future[Future[Unit]] = {
    val eos = rsp.data.isEmpty
    val f = new DefaultHttp2HeadersFrame(getHeaders(rsp.headers), eos)
    transport.write(f).map { _ =>
      rsp.data match {
        case None => Future.Unit
        case Some(data) =>
          val t = Stopwatch.start()
          val writing = writeStream(data)
          writing.onSuccess(_ => responseDurations.add(t().inMillis))
          writing
      }
    }
  }

  private[this] def writeStream(data: DataStream): Future[Unit] = {
    def loop(): Future[Unit] =
      data.read.flatMap {
        case DataStream.Data(buf, eos, release) =>
          val bb = BufAsByteBuf.Owned(buf)
          val data = new DefaultHttp2DataFrame(bb)
          transport.write(data).before {
            val released = release()
            if (eos) released
            else released.before(loop())
          }

        case DataStream.Trailers(headers) =>
          val f = new DefaultHttp2HeadersFrame(getHeaders(headers), true /* eos */ )
          transport.write(f)
      }

    loop()
  }

}
