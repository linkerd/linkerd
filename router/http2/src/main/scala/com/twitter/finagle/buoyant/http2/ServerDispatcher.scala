package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.{CancelledRequestException, Failure, Service}
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.io.{Reader, Writer}
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Promise, Return, Throw, Time}
import io.netty.handler.codec.http2._
import scala.collection.JavaConverters._

object ServerDispatcher {

  private case class Reading(
    data: Reader with Writer with Closable,
    trailers: Promise[Options[Headers]]
  )

  private case class Writing(
    reader: Reader,
    trailers: Future[Options[Headers]]
  )

  /**
   * A server's view of a Stream's state (see: RFC 7540 § 5.1).
   */
  private sealed trait State {
    def recv(frame: Http2StreamFrame): State
    def send(frame: Http2StreamFrame, finished: Future[Unit]): State
  }

  private object State {
    /**
     * The stream has been created but no messages have been
     * sent or received.
     */
    object Idle extends State {
      def recv(frame: Http2StreamFrame): State = frame match {
        case _: Http2WindowUpdateFrame => Idle
        case frame: Http2HeadersFrame =>
          val inrw, outrw = Reader.writable()
          val intrailers, outtrailers = Promise[Option[Headers]]
          Open(Reading(inrw, intrailers), Writing(outrw, outtrailers))
        case _ => Closed
      }
    }

    case class Open(remote: Reading, local: Option[Writing]) extends State

    case class HalfClosedLocal(remote: Reading) extends State
    case class HalfClosedRemote(local: Option[Writing]) extends State

    object Closed extends State

    case class Invalid(state: State, frame: Http2StreamFrame) extends State

    object LocalClosed {
      def unapply(state: State): Boolean = state match {
        case Open(_, _) => true
        case HalfClosedLocal(_) => true
        case _ => false
      }
    }

    object RemoteClosed {
      def unapply(state: State): Boolean = state match {
        case Open(_, _) => true
        case HalfClosedRemote() => true
        case _ => false
      }
    }

    object LocalOpen {
      def unapply(state: State): Option[Option[Writer]] = state match {
        case Open(_, w) => Some(w)
        case RemoteClosed(w) => Some(w)
        case _ => None
      }
    }

    object RemoteOpen {
      def unapply(state: State): Option[Reader] = state match {
        case Open(r, _) => Some(r)
        case LocalClosed(r) => Some(r)
        case _ => None
      }
    }
  }

  private class StreamManager {
    private[this] var stateRef: State = State.Idle
    def state: State = stateRef

    def close(): State = synchronized {
      state match {
        case LocalOpen(writing) =>
      }
    }

    def recv(frame: Http2Frame): (State, State) = synchronized {
      val orig = stateRef
      stateRef = (state, frame) match {
        case (_, _: Http2ResetFrame) => State.Closed

        case (State.Idle, f: Http2HeadersFrame) if f.isEndStream =>
          State.HalfClosedRemote(None)

        case (State.Idle, _: Http2HeadersFrame) =>
          val in = Reading(Reader.writable(), Promise[Option[Headers]])
          State.Open(Open(in, None))

        case (State.Open(_, out), frame) if frame.isEndStream => State.HalfClosedRemote(out)
        case (state@State.Open(_, _), _: Http2DataFrame) => state

        case (State.HalfClosedLocal(_), frame) if frame.isEndStream => State.Closed
        case (state@State.HalfClosedLocal(_), _) => state

        case (state, _: Http2WindowUpdateFrame) => state
        case (state, frame) => State.Invalid(state, frame)
      }
      (orig, stateRef)
    }

    def send(rsp: Response): (State, State) = synchronized {
      val orig = stateRef
      stateRef = (state, rsp.data) match {
        case (State.Open(remote, None), None) =>
          State.HalfClosedLocal(remote, None)

        case (State.HalfClosedRemote(None), None) =>
          State.Closed

        case (State.Open(remote, None), Some(data)) =>
          closeOnEnd(data.trailers)
          State.Open(remote, Some(Writing(data.reader, data.trailers)))

        case (State.HalfClosedRemote(None), Some(data)) =>
          closeOnEnd(data.trailers)
          State.HalfClosedRemote(Some(Writing(data.reader, data.trailers)))

        case (state, frame) => State.Invalid(state, frame)
      }
      (orig, stateRef)
    }

    private[this] def closeOnEnd(end: Future[_]): Unit = {
      val _ = end.respond { _ =>
        synchronized {
          stateRef = stateRef match {
            case Open(remote, Some(_)) => State.HalfClosedLocal(remote)
            case HalfClosedRemote(Some(_)) => State.Closed
            case state => state
          }
        }
      }
    }
  }
}

class ServerStreamTransport(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
  manager: StreamManager = new StreamManager
) extends Transport[Request, Response] {

  private[this] val log = Logger.get(getClass.getName)

  private[this] val cancelled = new CancelledRequestException

  def close(deadline: Time): Future[Unit] = {
    reading.raise(cancelled)
    transport.close(deadline)
  }

  def read(): Future[Request] =
    transport.read().flatMap { frame =>
      val (s0, s1) = manager.recv(frame)

      (s0, s1, frame) match {
        case (State.Idle, State.Idle, _) => read()

        case (State.Idle, State.Open(Reading(rw, trailers), _), frame: Http2HeadersFrame) =>
          log.info(s"srv.dispatch: ${transport.remoteAddress} read ${frame}")
          trailers.become(readStream(rw))
          Future.value(Request(RequestHeaders(frame.headers), Some(DataStream(rw, trailers))))

        case (State.Idle, State.HalClosedRemote(_), frame: Http2HeadersFrame) =>
          log.info(s"srv.dispatch: ${transport.remoteAddress} read ${frame}")
          Future.value(Request(RequestHeaders(frame.headers)))

        case (state0, state1, frame) =>
          val e = new IllegalStateException(s"$state0 -> $state1 with frame $frame")
          Future.exception(e)
      }
    }

  private[this] def readStream(
    writer: Writer with Closable,
    trailers: Promise[Option[Headers]]
  ): Future[Option[Headers]] = {
    def loop(): Future[Option[Headers]] =
      transport.read().flatMap { frame =>
        val (s0, s1) = manager.recv(frame)

        (s0, s1, frame) match {
          case (State.RemoteOpen(_), State.RemoteOpen(_), frame: Http2DataFrame) =>
            val buf = ByteBufAsBuf(frame.content)
            writer.write(buf).flatMap { _ => loop() }

          case (State.RemoteOpen(_), State.RemoteOpen(_), _) =>
            loop()

          case (State.RemoteOpen(_), State.RemoteClosed, frame: Http2DataFrame) =>
            val buf = ByteBufAsBuf(frame.content)
            writer.write(buf).flatMap { _ => writer.close() }.map { _ => None }

          case (State.RemoteOpen(_), State.RemoteClosed, frame: Http2HeadersFrame) =>
            writer.close().flatMap { _ => Some(Headers(frame.headers)) }

          case (state0, state1, frame) =>
            val e = new IllegalStateException(s"$state0 -> $state1 with frame $frame")
            Future.exception(e)
        }
      }

    loop()
  }

  def write(rsp: Response): Future[Future[Unit]] =
    manager.send(rsp) match {
      case (State.LocalOpen(None), State.LocalOpen(Some(Writing(reader, trailers)))) =>
        val hframe = new DefaultHttp2HeadersFrame(headers(rsp.headers), false /* eos */ )
        transport.write(hframe).map { _ =>
          writeStream(rsp.data.reader, rsp.trailers)
        }

      case (State.LocalOpen(None), State.LocalClosed) =>
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
        case None =>
          trailers.flatMap {
            case None =>
              val data = new DefaultHttp2DataFrame(true /* eos */ )
              transport.write(data)

            case Some(trailers) =>
              val data = new DefaultHttp2HeadersFrame(headers(trailers), true /* eos */ )
              transport.write(data)
          }

        case Some(buf) =>
          val data = new DefaultHttp2DataFrame(new BufAsByteBuf(buf))
          transport.write(data).flatMap { _ => loop() }
      }

    loop()
  }

}
