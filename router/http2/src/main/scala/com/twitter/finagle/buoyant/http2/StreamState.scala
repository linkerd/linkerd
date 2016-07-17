package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.io.{Reader, Writer}
import com.twitter.util.{Closable, Future, Promise}
import io.netty.handler.codec.http2._

private[http2] sealed trait Socket[T <: Socket.Active[T]]

private[http2] object Socket {
  sealed trait Open[T <: Socket.Active[T]] extends Socket[T]

  sealed trait Idle[T <: Socket.Active[T]] extends Open[T]
  sealed trait Active[T <: Socket.Active[T]] extends Open[T] {
    override def toString = "Active"
  }
  sealed trait Closed[T <: Socket.Active[T]] extends Socket[T] {
    override def toString = "Closed"
  }
}

case class Remote(
  receiver: Reader with Writer with Closable,
  trailers: Promise[Option[Headers]]
) extends Remote.Active {
  override def toString = "Remote.Active"
}

object Remote {
  sealed trait Open extends Socket.Open[Remote]
  object Open {
    def unapply(r: Open) = true
  }

  object Idle extends Socket.Idle[Remote] with Open {
    override def toString = "Remote.Idle"
  }

  sealed trait Active extends Socket.Active[Remote] with Open

  object Closed extends Socket.Closed[Remote] {
    override def toString = "Remote.Closed"
  }
}

case class Local(
  reader: Reader,
  trailers: Future[Option[Headers]]
) extends Local.Active {
  override def toString = "Local.Active"
}

object Local {
  sealed trait Open extends Socket.Open[Local]
  object Open {
    def unapply(l: Open) = true
  }

  object Idle extends Socket.Idle[Local] with Open {
    override def toString = "Local.Idle"
  }

  sealed trait Active extends Socket.Active[Local] with Open

  object Closed extends Socket.Closed[Local] {
    override def toString = "Local.Closed"
  }
}

private[http2] sealed trait StreamState {
  def remote: Socket[Remote]
  def local: Socket[Local]
}

private[http2] object StreamState {

  private[this] val log = com.twitter.logging.Logger.get(getClass.getName)

  object Idle extends StreamState {
    override def toString = "Idle"

    val remote = Remote.Idle
    val local = Local.Idle
  }

  case class Open(remote: Remote.Open, local: Local.Open)
    extends StreamState

  trait LocalClosed { _: StreamState =>
    val local = Local.Closed
  }

  trait RemoteClosed { _: StreamState =>
    val remote = Remote.Closed
  }

  case class HalfClosedLocal(remote: Remote.Open)
    extends StreamState
    with LocalClosed

  case class HalfClosedRemote(local: Local.Open)
    extends StreamState
    with RemoteClosed

  object Closed
    extends StreamState
    with LocalClosed
    with RemoteClosed {

    override def toString = "Closed"
  }

  case class Invalid(prior: StreamState, state: StreamState)
    extends StreamState
    with LocalClosed
    with RemoteClosed {

    override def toString = s"Invalid($prior, $state)"
  }

  object LocalOpen {
    def unapply(state: StreamState): Option[Local.Open] = state match {
      case Open(_, local) => Some(local)
      case HalfClosedRemote(local) => Some(local)
      case _ => None
    }
  }

  object RemoteOpen {
    def unapply(state: StreamState): Option[Socket.Open[Remote]] = state match {
      case Open(remote, _) => Some(remote)
      case HalfClosedLocal(remote) => Some(remote)
      case _ => None
    }
  }

  object LocalIdle {
    def unapply(state: StreamState): Boolean = state match {
      case LocalOpen(Local.Idle) => true
      case _ => false
    }
  }

  object RemoteIdle {
    def unapply(state: StreamState): Boolean = state match {
      case RemoteOpen(Remote.Idle) => true
      case _ => false
    }
  }

  object LocalClosed {
    def unapply(state: StreamState): Boolean = state match {
      case Closed | HalfClosedLocal(_) => true
      case _ => false
    }
  }

  object RemoteClosed {
    def unapply(state: StreamState): Boolean = state match {
      case Closed | HalfClosedRemote(_) => true
      case _ => false
    }
  }

  object LocalActive {
    def unapply(state: StreamState): Option[(Reader, Future[Option[Headers]])] =
      state match {
        case LocalOpen(Local(r, f)) => Some((r, f))
        case _ => None
      }
  }

  object RemoteActive {
    def unapply(state: StreamState): Option[(Reader with Writer with Closable, Promise[Option[Headers]])] =
      state match {
        case RemoteOpen(Remote(rw, p)) => Some((rw, p))
        case _ => None
      }
  }

  private object EndStream {
    def unapply(frame: Http2StreamFrame): Option[Http2StreamFrame] = frame match {
      case f: Http2HeadersFrame if f.isEndStream => Some(f)
      case f: Http2DataFrame if f.isEndStream => Some(f)
      case _ => None
    }
  }

  class Manager(stats: StatsReceiver = NullStatsReceiver) {
    private[this] var stateRef: StreamState = Idle
    def state: StreamState = synchronized(stateRef)

    def recv(frame: Http2StreamFrame): (StreamState, StreamState) = synchronized {
      val orig = stateRef
      stateRef = (orig, frame) match {
        // nop for now.
        case (state, _: Http2WindowUpdateFrame) => state

        // Game over, man.
        case (Closed, _) => Closed
        case (_, _: Http2ResetFrame) => Closed

        // Server receipt of a complete request.
        case (Idle, EndStream(f: Http2HeadersFrame)) => HalfClosedRemote(Local.Idle)

        // Server receipt of a request, opening a stream.
        case (Idle, _: Http2HeadersFrame) =>
          val remote = Remote(Reader.writable(), Promise[Option[Headers]])
          Open(remote, Local.Idle)

        // Client receipt of a complete response.
        case (Open(_, local), EndStream(_)) => HalfClosedRemote(local)
        case (HalfClosedLocal(_), EndStream(_)) => Closed

        // Client receipt of a streaming response.
        case (Open(Remote.Idle, local), f: Http2HeadersFrame) =>
          val remote = Remote(Reader.writable(), Promise[Option[Headers]])
          Open(remote, local)
        case (HalfClosedLocal(Remote.Idle), f: Http2HeadersFrame) =>
          val remote = Remote(Reader.writable(), Promise[Option[Headers]])
          HalfClosedLocal(remote)

        // Streaming data.
        case (state@RemoteOpen(_), _: Http2DataFrame) => state

        // Something untoward.
        case (state, _) => Invalid(orig, state)
      }
      (orig, stateRef)
    }

    def send(msg: Message): (StreamState, StreamState) = synchronized {
      val orig = stateRef
      stateRef = (orig, msg.data) match {
        case (Idle, None) => HalfClosedLocal(Remote.Idle)

        case (Idle, Some(DataStream(reader, trailers))) =>
          val local = Local(reader, trailers)
          trailers.ensure(closeLocal())
          Open(Remote.Idle, local)

        case (Open(remote, Local.Idle), Some(DataStream(reader, trailers))) =>
          trailers.ensure(closeLocal())
          Open(remote, Local(reader, trailers))

        case (Open(remote, Local.Idle), None) =>
          HalfClosedLocal(remote)

        case (HalfClosedRemote(Local.Idle), Some(DataStream(reader, trailers))) =>
          trailers.ensure(closeLocal())
          HalfClosedRemote(Local(reader, trailers))

        case (HalfClosedRemote(Local.Open()), None) => Closed

        case (orig, _) => Invalid(orig, orig)
      }
      (orig, stateRef)
    }

    private[this] def closeLocal(): Unit =
      synchronized {
        val orig = stateRef
        stateRef = orig match {
          case Open(remote, _) => HalfClosedLocal(remote)
          case HalfClosedRemote(_) => Closed
          case state => state
        }
      }

    private[this] val openGauge = stats.addGauge("open") {
      state match {
        case Open(_, _) => 1f
        case _ => 0f
      }
    }
    private[this] val halfClosedLocalGauge = stats.addGauge("half_closed_local") {
      state match {
        case HalfClosedLocal(_) => 1f
        case _ => 0f
      }
    }
    private[this] val halfClosedRemoteGauge = stats.addGauge("half_closed_remote") {
      state match {
        case HalfClosedRemote(_) => 1f
        case _ => 0f
      }
    }
    private[this] val closedGauge = stats.addGauge("closed") {
      state match {
        case Closed => 1f
        case _ => 0f
      }
    }
    private[this] val invalidGauge = stats.addGauge("invalid") {
      state match {
        case Invalid(_, _) => 1f
        case _ => 0f
      }
    }

  }
}
