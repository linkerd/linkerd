package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Failure
import com.twitter.finagle.stats.{StatsReceiver => FStatsReceiver, NullStatsReceiver => FNullStatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw, Try}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.annotation.tailrec
import scala.collection.immutable.Queue

/**
 * Models a single HTTP/2 stream.
 *
 * Transports send a `Local`-typed message via an underlying
 * [[H2Transport.Writer]]. A dispatcher, which models a single HTTP/2
 * connection, provides the transport with `Http2StreamFrame`
 * instances that are used to build a `RemoteMsg`-typed message.
 */
private[h2] trait Netty4StreamTransport[LocalMsg <: Message, RemoteMsg <: Message] {
  import Netty4StreamTransport._

  /** The HTTP/2 STREAM_ID of this stream. */
  def streamId: Int

  protected[this] def transport: H2Transport.Writer

  protected[this] def statsReceiver: StatsReceiver

  /*
   * A stream's state is represented by the `StreamState` ADT,
   * reflecting the state diagram detailed in RFC7540 §5.1:
   *
   *                       +--------+
   *               recv ES |        | send ES
   *               ,-------|  open  |-------.
   *              /        |        |        \
   *             v         +--------+         v
   *     +----------+          |           +----------+
   *     |   half   |          |           |   half   |
   *     |  closed  |          | send R /  |  closed  |
   *     | (remote) |          | recv R    | (local)  |
   *     +----------+          |           +----------+
   *          |                |                 |
   *          | send ES /      |       recv ES / |
   *          | send R /       v        send R / |
   *          | recv R     +--------+   recv R   |
   *          `----------->|        |<-----------'
   *                       | closed |
   *                       |        |
   *                       +--------+
   *
   *
   * (Note that SERVER_PUSH is not supported or represented in this
   * version of the state diagram).
   */

  private[this] sealed trait StreamState
  private[this] case class Open(remote: RemoteState) extends StreamState
  private[this] case class LocalClosed(remote: RemoteState) extends StreamState
  private[this] case class RemoteClosed(q: AsyncQueue[Frame]) extends StreamState
  private[this] case class Closed(error: Reset) extends StreamState

  private[this] sealed trait RemoteState
  private[this] case class RemotePending(p: Promise[RemoteMsg]) extends RemoteState
  private[this] case class RemoteStreaming(q: AsyncQueue[Frame]) extends RemoteState
  private[this] object RemoteOpen {
    def unapply(s: StreamState): Option[RemoteState] = s match {
      case Open(r) => Some(r)
      case LocalClosed(r) => Some(r)
      case _ => None
    }
  }
  private[this] val closedClosed = Closed(Reset.Closed)

  private[this] val remoteMsgP = new Promise[RemoteMsg]
  private[this] val localResetP = new Promise[Reset]

  def remoteMsg: Future[RemoteMsg] = remoteMsgP

  // When the remote message--especially a client's repsonse--is
  // canceled, close the transport, sending a RST_STREAM as
  // appropriate.
  remoteMsgP.setInterruptHandler {
    case err: Reset =>
      reset(err); ()

    case Failure(Some(err: Reset)) =>
      reset(err); ()

    case f@Failure(_) if f.isFlagged(Failure.Interrupted) =>
      reset(Reset.Cancel); ()

    case f@Failure(_) if f.isFlagged(Failure.Rejected) =>
      reset(Reset.Refused); ()

    case e =>
      reset(Reset.InternalError); ()
  }

  /**
   * Because remote reads and local writes may occur concurrently,
   * this state is stored in the `stateRef` atomic reference. Writes
   * and reads are performed without locking stateRef (instead, callers )
   */
  private[this] val stateRef: AtomicReference[StreamState] =
    new AtomicReference(Open(RemotePending(remoteMsgP)))

  private[this] val closeP = new Promise[Reset]
  def onClose: Future[Reset] = closeP
  def isClosed = stateRef.get match {
    case Closed(_) => true
    case _ => false
  }

  protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): RemoteMsg

  /**
   * Signals a reset from Local to Remote by setting the stream state
   * to Closed.
   */
  @tailrec final def reset(err: Reset): Boolean =
    stateRef.get match {
      case Closed(_) => false

      case ro@RemoteOpen(remote) =>
        if (stateRef.compareAndSet(ro, Closed(err))) {
          remote match {
            case RemotePending(remoteP) =>
              remoteP.setException(err)
            case RemoteStreaming(remoteQ) =>
              remoteQ.fail(err, discard = true)
          }
          closeP.setValue(err)
          true
        } else reset(err)

      case rc@RemoteClosed(remoteQ) =>
        if (stateRef.compareAndSet(rc, Closed(err))) {
          remoteQ.fail(err, discard = true)
          closeP.setValue(err)
          true
        } else reset(err)
    }

  /**
   * Optionally accept a frame from the remote side of a connection.
   * s   *
   * `admitRemote` returns false to indicate that a frame cold not be
   * accepted.  This may occur, for example, when a message is
   * received on a closed stream.
   *
   * Remote frames that are received and demultiplexed by a Dispatcher
   * are offered onto the stream. Each frame
   */
  @tailrec final def admitRemote(in: Http2StreamFrame): Option[Error] = in match {
    case rst: Http2ResetFrame =>
      stateRef.get match {
        case Closed(err) => Some(err)

        case open@RemoteOpen(remote) =>
          val err = toReset(rst.errorCode)
          if (stateRef.compareAndSet(open, Closed(err))) {
            statsReceiver.remoteResetCount.incr()
            remote match {
              case RemotePending(remoteP) =>
                remoteP.setException(err)
              case RemoteStreaming(remoteQ) =>
                remoteQ.fail(err, discard = true)
            }
            closeP.setValue(err)
            None
          } else admitRemote(rst)

        case rc@RemoteClosed(remoteQ) =>
          val err = toReset(rst.errorCode)
          if (stateRef.compareAndSet(rc, Closed(err))) {
            remoteQ.fail(err, discard = true)
            closeP.setValue(err)
            None
          } else admitRemote(rst)
      }

    case hdrs: Http2HeadersFrame if hdrs.isEndStream =>
      stateRef.get match {
        case Closed(err) => Some(err)

        case rc@RemoteClosed(remoteQ) =>
          if (stateRef.compareAndSet(rc, closedClosed)) {
            remoteQ.fail(Reset.Closed, discard = true)
            someClosed
          } else admitRemote(hdrs)

        case open@Open(remote) => remote match {
          case RemotePending(remoteP) =>
            val remoteQ = new AsyncQueue[Frame]
            if (stateRef.compareAndSet(open, RemoteClosed(remoteQ))) {
              val msg = mkRemoteMsg(hdrs.headers, Stream.empty(remoteQ))
              remoteP.setValue(msg)
              None
            } else admitRemote(hdrs)

          case RemoteStreaming(remoteQ) =>
            val rc = RemoteClosed(remoteQ)
            if (stateRef.compareAndSet(open, rc)) {
              val f = toFrame(hdrs)
              statsReceiver.recordRemoteFrame(f)

              if (remoteQ.offer(f)) None
              else if (stateRef.compareAndSet(rc, closedClosed)) {
                remoteQ.fail(Reset.Closed, discard = true)
                someClosed
              } else admitRemote(hdrs)
            } else admitRemote(hdrs)
        }

        case lc@LocalClosed(remote) => remote match {
          case RemotePending(remoteP) =>
            if (stateRef.compareAndSet(lc, Closed(Reset.NoError))) {
              val msg = mkRemoteMsg(hdrs.headers, NilStream)
              remoteP.setValue(msg)
              closeP.setValue(Reset.NoError)
              None
            } else admitRemote(hdrs)

          case RemoteStreaming(remoteQ) =>
            if (stateRef.compareAndSet(lc, Closed(Reset.NoError))) {
              val f = toFrame(hdrs)
              statsReceiver.recordRemoteFrame(f)
              if (remoteQ.offer(f)) {
                remoteQ.fail(Reset.NoError, discard = false)
                closeP.setValue(Reset.NoError)
                None
              } else someClosed
            } else admitRemote(hdrs)
        }
      }

    case hdrs: Http2HeadersFrame =>
      // A HEADERS frame without END_STREAM may only be received to
      // initiate a message (i.e. when the remote is still pending).
      stateRef.get match {
        case Closed(err) => Some(err)

        case open@Open(RemotePending(remoteP)) =>
          val remoteQ = new AsyncQueue[Frame]
          if (stateRef.compareAndSet(open, Open(RemoteStreaming(remoteQ)))) {
            val msg = mkRemoteMsg(hdrs.headers, Stream(remoteQ))
            remoteP.setValue(msg)
            None
          } else admitRemote(hdrs)

        case lc@LocalClosed(RemotePending(remoteP)) =>
          val remoteQ = new AsyncQueue[Frame]
          if (stateRef.compareAndSet(lc, LocalClosed(RemoteStreaming(remoteQ)))) {
            val msg = mkRemoteMsg(hdrs.headers, Stream(remoteQ))
            remoteP.setValue(msg)
            None
          } else admitRemote(hdrs)

        case rc@RemoteClosed(remoteQ) =>
          if (stateRef.compareAndSet(rc, closedClosed)) {
            remoteQ.fail(Reset.Closed, discard = true)
            closeP.setValue(Reset.Closed)
            someClosed
          } else admitRemote(hdrs)

        case ro@RemoteOpen(RemoteStreaming(remoteQ)) =>
          if (stateRef.compareAndSet(ro, closedClosed)) {
            remoteQ.fail(Reset.Closed, discard = true)
            closeP.setValue(Reset.Closed)
            someClosed
          } else admitRemote(hdrs)
      }

    case data: Http2DataFrame =>
      stateRef.get match {
        case Closed(err) => Some(err)

        case rc@RemoteClosed(remoteQ) =>
          if (stateRef.compareAndSet(rc, closedClosed)) {
            remoteQ.fail(Reset.Closed, discard = true)
            closeP.setValue(Reset.Closed)
            someClosed
          } else admitRemote(data)

        case ro@RemoteOpen(RemotePending(remoteP)) =>
          if (stateRef.compareAndSet(ro, closedClosed)) {
            remoteP.setException(Reset.Closed)
            closeP.setValue(Reset.Closed)
            someClosed
          } else admitRemote(data)

        case open@Open(RemoteStreaming(remoteQ)) =>
          if (data.isEndStream) {
            val rc = RemoteClosed(remoteQ)
            if (stateRef.compareAndSet(open, rc)) {
              val f = toFrame(data)
              statsReceiver.recordRemoteFrame(f)

              if (remoteQ.offer(f)) None
              else if (stateRef.compareAndSet(rc, closedClosed)) {
                remoteQ.fail(Reset.Closed, discard = true)
                closeP.setValue(Reset.Closed)
                someClosed
              } else admitRemote(data)
            } else admitRemote(data)
          } else {
            val f = toFrame(data)
            if (remoteQ.offer(f)) {
              statsReceiver.recordRemoteFrame(f)
              None
            } else if (stateRef.compareAndSet(open, closedClosed)) {
              remoteQ.fail(Reset.Closed, discard = true)
              closeP.setValue(Reset.Closed)
              someClosed
            } else admitRemote(data)
          }

        case lc@LocalClosed(RemoteStreaming(remoteQ)) =>
          if (data.isEndStream) {
            if (stateRef.compareAndSet(lc, Closed(Reset.NoError))) {
              val f = toFrame(data)
              statsReceiver.recordRemoteFrame(f)
              closeP.setValue(Reset.NoError)

              if (remoteQ.offer(f)) {
                remoteQ.fail(Reset.NoError, discard = false)
                None
              } else someClosed
            } else admitRemote(data)
          } else {
            val f = toFrame(data)
            if (remoteQ.offer(f)) {
              statsReceiver.recordRemoteFrame(f)
              None
            } else if (stateRef.compareAndSet(lc, closedClosed)) {
              remoteQ.fail(Reset.Closed, discard = true)
              closeP.setValue(Reset.Closed)
              someClosed
            } else admitRemote(data)
          }
      }
  }

  private[this] val updateWindow: Int => Future[Unit] =
    incr => transport.updateWindow(streamId, incr)

  private[this] def toFrame(f: Http2StreamFrame): Frame = f match {
    case f: Http2DataFrame => Netty4Message.Data(f, updateWindow)
    case f: Http2HeadersFrame if f.isEndStream => Netty4Message.Trailers(f.headers)
    case f => throw new IllegalArgumentException(s"invalid stream frame: ${f}")
  }

  def write(msg: LocalMsg): Future[Future[Unit]] = {
    val headersF =
      if (msg.stream.isEmpty) writeHeadersEos(msg.headers)
      else writeHeadersInit(msg.headers)

    headersF.map(_ => writeStream(msg.stream))
  }

  private[this] def writeHeaders(hdrs: Headers, eos: Boolean): Future[Unit] = {
    val p = new Promise[Unit]
    val f = transport.write(streamId, hdrs, eos)
    f.respond { v => p.updateIfEmpty(v); () }
    p.setInterruptHandler {
      case err: Reset =>
        f.raise(err)
        p.updateIfEmpty(Throw(err)); ()
    }
    p
  }

  private[this] def writeHeadersInit(hdrs: Headers): Future[Unit] =
    stateRef.get match {
      case Closed(err) => Future.exception(err)
      case LocalClosed(_) => Future.exception(Reset.Closed)
      case _ => writeHeaders(hdrs, false)
    }

  @tailrec private[this] def writeHeadersEos(hdrs: Headers): Future[Unit] =
    stateRef.get match {
      case Closed(err) => Future.exception(err)

      case LocalClosed(_) =>
        Future.exception(Reset.Closed)

      case open@Open(remote) =>
        if (stateRef.compareAndSet(open, LocalClosed(remote))) writeHeaders(hdrs, true)
        else writeHeadersEos(hdrs)

      case rc@RemoteClosed(remoteQ) =>
        if (stateRef.compareAndSet(rc, Closed(Reset.NoError))) {
          remoteQ.fail(Reset.NoError, discard = false)
          closeP.setValue(Reset.NoError)
          writeHeaders(hdrs, true)
        } else writeHeadersEos(hdrs)
    }

  @tailrec private[this] def _writeFrame(frame: Frame): Future[Unit] =
    stateRef.get match {
      case Closed(err) => Future.exception(err)

      case lc@LocalClosed(remote) =>
        if (stateRef.compareAndSet(lc, closedClosed)) {
          val err = remote match {
            case RemotePending(remoteP) =>
              remoteP.setException(Reset.Closed)
            case RemoteStreaming(remoteQ) =>
              remoteQ.fail(Reset.Closed, discard = true)
          }
          closeP.setValue(Reset.Closed)
          Future.exception(Reset.Closed)
        } else _writeFrame(frame)

      case _ =>
        statsReceiver.recordLocalFrame(frame)
        transport.write(streamId, frame).before(frame.release())
    }

  @tailrec private[this] def _writeFrameEos(frame: Frame): Future[Unit] =
    stateRef.get match {
      case Closed(err) => Future.exception(err)

      case lc@LocalClosed(remote) =>
        if (stateRef.compareAndSet(lc, closedClosed)) {
          val err = remote match {
            case RemotePending(remoteP) =>
              remoteP.setException(Reset.Closed); ()
            case RemoteStreaming(remoteQ) =>
              remoteQ.fail(Reset.Closed, discard = true)
          }
          closeP.setValue(Reset.Closed)
          Future.exception(Reset.Closed)
        } else _writeFrameEos(frame)

      case open@Open(remote) =>
        if (stateRef.compareAndSet(open, LocalClosed(remote))) {
          statsReceiver.recordLocalFrame(frame)
          transport.write(streamId, frame).before(frame.release())
        } else _writeFrameEos(frame)

      case rc@RemoteClosed(remoteQ) =>
        if (stateRef.compareAndSet(rc, Closed(Reset.NoError))) {
          statsReceiver.recordLocalFrame(frame)
          remoteQ.fail(Reset.NoError, discard = false)
          closeP.setValue(Reset.NoError)
          transport.write(streamId, frame).before(frame.release())
        } else _writeFrameEos(frame)
    }

  private[this] val writeFrame: Frame => Future[Unit] = {
    case f if !f.isEnd => _writeFrame(f)
    case f => _writeFrameEos(f)
  }

  /** Write a request stream to the underlying transport */
  private[this] def writeStream(stream: Stream): Future[Unit] = {
    // Read from the stream until it fails.
    def loop(): Future[Unit] =
      stream.read().flatMap(writeFrame).before(loop())
    val doneF = loop()

    val streamP = new Promise[Unit]
    doneF.respond {
      case done@Return(_) =>
        streamP.updateIfEmpty(done); ()

      case e@Throw(rst: Reset) =>
        reset(rst)
        streamP.updateIfEmpty(e); ()

      case e@Throw(_) =>
        reset(Reset.InternalError)
        streamP.updateIfEmpty(e); ()
    }
    streamP.setInterruptHandler {
      case rst: Reset =>
        if (streamP.updateIfEmpty(Throw(rst))) {
          doneF.raise(rst)
        }
    }
    streamP
  }

}

object Netty4StreamTransport {
  private val log = Logger.get(getClass.getName)

  private val someClosed = Some(Reset.Closed)

  private object NilStream extends Stream {
    override def isEmpty = true
    override def onEnd = Future.Unit
    override def read(): Future[Frame] = Future.exception(Reset.NoError)
  }

  private def toReset(code: Long): Reset =
    Http2Error.valueOf(code) match {
      case Http2Error.NO_ERROR => Reset.NoError
      case Http2Error.INTERNAL_ERROR => Reset.InternalError
      case Http2Error.ENHANCE_YOUR_CALM => Reset.EnhanceYourCalm
      case Http2Error.REFUSED_STREAM => Reset.Refused
      case Http2Error.STREAM_CLOSED => Reset.Closed
      case Http2Error.CANCEL => Reset.Cancel
      case err => throw new IllegalArgumentException(s"invalid stream error: ${err}")
    }

  class StatsReceiver(underlying: FStatsReceiver) {
    private[this] val local = underlying.scope("local")
    private[this] val localDataBytes = local.stat("data", "bytes")
    private[this] val localDataFrames = local.counter("data", "frames")
    private[this] val localTrailersCount = local.counter("trailers")
    val localResetCount = local.counter("reset")
    val recordLocalFrame: Frame => Unit = {
      case d: Frame.Data =>
        localDataFrames.incr()
        localDataBytes.add(d.buf.length)
      case t: Frame.Trailers => localTrailersCount.incr()
    }

    private[this] val remote = underlying.scope("remote")
    private[this] val remoteDataBytes = remote.stat("data", "bytes")
    private[this] val remoteDataFrames = remote.counter("data", "frames")
    private[this] val remoteTrailersCount = remote.counter("trailers")
    val remoteResetCount = remote.counter("reset")
    val recordRemoteFrame: Frame => Unit = {
      case d: Frame.Data =>
        remoteDataFrames.incr()
        remoteDataBytes.add(d.buf.length)
      case _: Frame.Trailers => remoteTrailersCount.incr()
    }

  }

  object NullStatsReceiver extends StatsReceiver(FNullStatsReceiver)

  private class Client(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Request, Response] {

    override protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): Response =
      Response(Netty4Message.Headers(headers), stream)
  }

  private class Server(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Response, Request] {

    override protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): Request =
      Request(Netty4Message.Headers(headers), stream)
  }

  def client(
    id: Int,
    writer: H2Transport.Writer,
    stats: StatsReceiver = NullStatsReceiver
  ): Netty4StreamTransport[Request, Response] =
    new Client(id, writer, stats)

  def server(
    id: Int,
    writer: H2Transport.Writer,
    stats: StatsReceiver = NullStatsReceiver
  ): Netty4StreamTransport[Response, Request] =
    new Server(id, writer, stats)

}
