package com.twitter.finagle.buoyant.http2

import com.twitter.io.{Reader, Writer}
import com.twitter.util.{Closable, Future, Promise}
import io.netty.handler.codec.http2._

private[http2] sealed trait StreamState
private[http2] object StreamState {

  case class Recv(
    data: Reader with Writer with Closable,
    trailers: Promise[Option[Headers]]
  )

  case class Send(
    reader: Reader,
    trailers: Future[Option[Headers]]
  )

  object Idle extends StreamState

  case class Open(recv: Option[Recv], send: Option[Send]) extends StreamState

  case class HalfClosedLocal(recv: Option[Recv]) extends StreamState
  case class HalfClosedRemote(send: Option[Send]) extends StreamState

  object Closed extends StreamState

  case class Invalid(state: StreamState) extends StreamState

  object LocalClosed {
    def unapply(state: StreamState): Boolean = state match {
      case Open(_, _) => true
      case HalfClosedLocal(_) => true
      case _ => false
    }
  }

  object RemoteClosed {
    def unapply(state: StreamState): Boolean = state match {
      case Open(_, _) => true
      case HalfClosedRemote(_) => true
      case _ => false
    }
  }

  object LocalOpen {
    def unapply(state: StreamState): Option[Option[Send]] = state match {
      case Open(_, w) => Some(w)
      case HalfClosedRemote(w) => Some(w)
      case _ => None
    }
  }

  object RemoteOpen {
    def unapply(state: StreamState): Option[Option[Recv]] = state match {
      case Open(r, _) => Some(r)
      case HalfClosedLocal(r) => Some(r)
      case _ => None
    }
  }

  private object EndFrame {
    def unapply(frame: Http2StreamFrame): Boolean = frame match {
      case f: Http2HeadersFrame => f.isEndStream
      case f: Http2DataFrame => f.isEndStream
      case _: Http2ResetFrame => true
    }
  }

  class Manager {
    private[this] var stateRef: StreamState = Idle
    def state: StreamState = stateRef

    def recv(frame: Http2StreamFrame): (StreamState, StreamState) = synchronized {
      val orig = stateRef
      stateRef = (state, frame) match {
        case (_, _: Http2ResetFrame) => Closed

        case (Idle, f: Http2HeadersFrame) if f.isEndStream =>
          HalfClosedRemote(None)

        case (Idle, _: Http2HeadersFrame) =>
          val in = Recv(Reader.writable(), Promise[Option[Headers]])
          Open(Some(in), None)

        case (Open(_, out), EndFrame()) => HalfClosedRemote(out)
        case (state@Open(_, _), _: Http2DataFrame) => state

        case (HalfClosedLocal(_), EndFrame()) => Closed
        case (state@HalfClosedLocal(_), _) => state

        case (state, _: Http2WindowUpdateFrame) => state
        case (state, _) => Invalid(state)
      }
      (orig, stateRef)
    }

    def send(msg: Message): (StreamState, StreamState) = synchronized {
      val orig = stateRef
      stateRef = (state, msg.data) match {
        case (Idle, None) => HalfClosedLocal(None)

        case (Idle, Some(data)) =>
          val out = Send(data.reader, data.trailers)
          closeLocalOn(data.trailers)
          Open(None, Some(out))

        case (Open(in, None), Some(data)) =>
          closeLocalOn(data.trailers)
          Open(in, Some(Send(data.reader, data.trailers)))

        case (Open(in, None), None) => HalfClosedLocal(in)

        case (HalfClosedRemote(None), Some(data)) =>
          closeLocalOn(data.trailers)
          HalfClosedRemote(Some(Send(data.reader, data.trailers)))

        case (HalfClosedRemote(None), None) => Closed

        case (state, _) => Invalid(state)
      }
      (orig, stateRef)
    }

    private[this] def closeLocalOn(end: Future[_]): Unit = {
      val _ = end.respond { _ =>
        synchronized {
          stateRef = stateRef match {
            case Open(remote, Some(_)) => HalfClosedLocal(remote)
            case HalfClosedRemote(Some(_)) => Closed
            case state => state
          }
        }
      }
    }

  }
}
