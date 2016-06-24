package io.buoyant.router.http2

import com.twitter.io.Buf

/**
 * A Stream's state, as described by RFC7540 §5.1.
 */
sealed trait StreamState
object StreamState {
  object Idle extends StreamState {
    def onPushPromiseSend: StreamState = Reserved.Local
    def onPushPromiseRecv: StreamState = Reserved.Remote
  }

  sealed trait Reserved extends StreamState
  object Reserved {
    object Local extends Reserved {
      def onHeadersSend: StreamState = HalfClosed.Local
    }
    object Remote extends Reserved {
      def onHeadersRecv: StreamState = HalfClosed.Remote
    }
  }

  object Open extends StreamState {
    def onEndStreamSend: StreamState = HalfClosed.Local
    def onEndStreamRecv: StreamState = HalfClosed.Remote
  }

  sealed trait HalfClosed extends StreamState
  object HalfClosed {
    object Local extends HalfClosed {
      def onEndStreamSend: StreamState = HalfClosed.Local
      def onEndStreamRecv: StreamState = HalfClosed.Remote
    }
    object Remote extends HalfClosed {
      def onEndStreamSend: StreamState = HalfClosed.Local
      def onEndStreamRecv: StreamState = HalfClosed.Remote
    }
  }

  object Closed extends StreamState
}

case class Stream(id: Int, state: StreamState)

sealed trait Frame {
  def stream: Stream
  def flags: Buf
  def endStream: Boolean
}

object Frame {
  case class Data(id: Buf, flags: Buf, payload: Buf) extends Frame {
    def endStream: Boolean
  }

  trait Headers extends Frame {
    def endHeaders: Boolean
    def endStream: Boolean
  }

  trait Continuation extends Frame {
    def endHeaders: Boolean
    def endStream: Boolean
  }

  case class Priority(id: Buf, flags: Buf) extends Frame

  case class ResetStream(id: Buf, flags: Buf) extends Frame

  case class Settings(id: Buf, flags: Buf) extends Frame

  case class PushPromise(id: Buf, flags: Buf) extends Frame

  case class Ping(id: Buf, flags: Buf) extends Frame

  case class GoAway(id: Buf, flags: Buf) extends Frame

  case class WindowUpdate(id: Buf, flags: Buf) extends Frame

}

sealed trait Error
case class ProtocolError(msg: String) extends Error
case class ConnectionError(msg: String) extends Error
case class StreamError(stream: Stream, msg: String) extends Error
