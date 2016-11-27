package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.{ChannelClosedException, ChannelWriteException, Failure}
import com.twitter.finagle.stats.{StatsReceiver => FStatsReceiver, NullStatsReceiver => FNullStatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw, Try}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace

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

  /** for logging */
  protected[this] def prefix: String

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

  private[this] trait ResettableState {
    def reset(rst: Reset): Unit
  }
  private[this] trait ClosableState { _: ResettableState =>
    def close(): Unit
  }

  private[this] sealed trait StreamState

  private[this] case class Open(remote: RemoteState) extends StreamState with ResettableState {
    override def reset(rst: Reset): Unit = remote.reset(rst)
  }

  private[this] case class LocalClosed(remote: RemoteState)
    extends StreamState with ResettableState {

    override def reset(rst: Reset): Unit = remote.reset(rst)
  }

  private[this] case class Closed(error: Reset) extends StreamState
  private[this] val closedState = Closed(Reset.Closed)

  private[this] class RemoteClosed(q: AsyncQueue[Frame])
    extends StreamState with ResettableState with ClosableState {

    override def reset(rst: Reset): Unit = q.fail(rst, discard = true)
    override def close(): Unit = q.fail(Reset.NoError, discard = false)
  }
  private[this] object RemoteClosed {
    def unapply(rc: RemoteClosed): Boolean = true
  }

  private[this] sealed trait RemoteState extends ResettableState

  private[this] class RemotePending(p: Promise[RemoteMsg]) extends RemoteState {
    def setMessage(msg: RemoteMsg): Unit = p.setValue(msg)
    override def reset(rst: Reset): Unit = p.setException(rst)
  }
  private[this] object RemotePending {
    def unapply(rs: RemotePending): Boolean = true
  }

  private[this] class RemoteStreaming(q: AsyncQueue[Frame])
    extends RemoteState with ClosableState {
    def toRemoteClosed: RemoteClosed = new RemoteClosed(q)

    def offer(f: Frame): Boolean = q.offer(f)
    override def reset(rst: Reset): Unit = q.fail(rst, discard = true)
    override def close(): Unit = q.fail(Reset.NoError, discard = false)
  }
  private[this] object RemoteStreaming {
    def apply(q: AsyncQueue[Frame]): RemoteStreaming = new RemoteStreaming(q)
    def unapply(rs: RemoteStreaming): Boolean = true
  }

  private[this] object RemoteOpen {
    def unapply(s: StreamState): Option[RemoteState] = s match {
      case Open(r) => Some(r)
      case LocalClosed(r) => Some(r)
      case Closed(_) | RemoteClosed() => None
    }
  }

  private[this] object LocalOpen {
    def unapply(s: StreamState): Boolean = s match {
      case Open(_) | RemoteClosed() => true
      case Closed(_) | LocalClosed(_) => false
    }
  }

  private[this] val remoteMsgP = new Promise[RemoteMsg]
  private[this] val localResetP = new Promise[Reset]

  def remoteMsg: Future[RemoteMsg] = remoteMsgP

  // When the remote message--especially a client's repsonse--is
  // canceled, close the transport, sending a RST_STREAM as
  // appropriate.
  remoteMsgP.setInterruptHandler {
    case err: Reset =>
      log.debug(err, "[%s] remote message interrupted", prefix)
      localReset(err)

    case Failure(Some(err: Reset)) =>
      log.debug(err, "[%s] remote message interrupted", prefix)
      localReset(err)

    case f@Failure(_) if f.isFlagged(Failure.Interrupted) =>
      log.debug(f, "[%s] remote message interrupted", prefix)
      localReset(Reset.Cancel)

    case f@Failure(_) if f.isFlagged(Failure.Rejected) =>
      log.debug(f, "[%s] remote message interrupted", prefix)
      localReset(Reset.Refused)

    case e =>
      log.debug(e, "[%s] remote message interrupted", prefix)
      localReset(Reset.InternalError)
  }

  /**
   * Because remote reads and local writes may occur concurrently,
   * this state is stored in the `stateRef` atomic reference. Writes
   * and reads are performed without locking stateRef (instead, callers )
   */
  private[this] val stateRef: AtomicReference[StreamState] =
    new AtomicReference(Open(new RemotePending(remoteMsgP)))

  /**
   * Satisfied successfully when the stream is fully closed with no
   * error.  An exception is raised with a Reset if the stream is
   * closed prematurely.
   */
  def onReset: Future[Unit] = resetP
  private[this] val resetP = new Promise[Unit]

  def isClosed = stateRef.get match {
    case Closed(_) => true
    case _ => false
  }

  protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): RemoteMsg

  def remoteReset(err: Reset): Unit =
    if (tryReset(err)) resetP.setException(StreamError.Remote(err))

  def localReset(err: Reset): Unit =
    if (tryReset(err)) resetP.setException(StreamError.Local(err))

  @tailrec private[this] def tryReset(err: Reset): Boolean =
    stateRef.get match {
      case state: StreamState with ResettableState =>
        if (stateRef.compareAndSet(state, Closed(err))) {
          log.debug("[%s] resetting %s in %s", prefix, err, state)
          state.reset(err)
          true
        } else tryReset(err)

      case _ => false
    }

  @tailrec private[this] def closeLocal(): Unit =
    stateRef.get match {
      case Closed(_) =>

      case state@LocalClosed(remote) =>
        if (stateRef.compareAndSet(state, closedState)) {
          remote.reset(Reset.InternalError)
          resetP.setException(new IllegalStateException("closing local from LocalClosed"))
        } else closeLocal()

      case state@Open(remote) =>
        if (!stateRef.compareAndSet(state, LocalClosed(remote))) closeLocal()

      case state@RemoteClosed() =>
        if (stateRef.compareAndSet(state, Closed(Reset.NoError))) {
          state.close()
          resetP.setDone(); ()
        } else closeLocal()
    }

  /*
   * Reading a RemoteMsg from the remote.
   */

  /**
   * Optionally accept a frame from the remote side of a connection.
   *
   * `admitRemote` returns false to indicate that a frame cold not be
   * accepted.  This may occur, for example, when a message is
   * received on a closed stream.
   *
   * Remote frames that are received and demultiplexed by a Dispatcher
   * are offered onto the stream. Each frame
   */
  @tailrec final def admitRemote(in: Http2StreamFrame): Boolean = {
    val state = stateRef.get
    log.trace("[%s] admitting %s in %s", prefix, in.name, state)

    def resetRemote(remote: ResettableState, rst: Reset): Boolean =
      if (stateRef.compareAndSet(state, Closed(rst))) {
        remote.reset(rst)
        resetP.setException(StreamError.Remote(rst))
        true
      } else false

    def admitFrame(f: Frame, remote: RemoteStreaming): Boolean =
      if (remote.offer(f)) {
        statsReceiver.recordRemoteFrame(f)
        true
      } else false

    in match {
      case rst: Http2ResetFrame =>
        state match {
          case Closed(_) => false

          case RemoteOpen(remote) =>
            if (resetRemote(remote, toReset(rst.errorCode))) {
              statsReceiver.remoteResetCount.incr()
              true
            } else admitRemote(rst)

          case state@RemoteClosed() =>
            if (resetRemote(state, toReset(rst.errorCode))) {
              statsReceiver.remoteResetCount.incr()
              true
            } else admitRemote(rst)
        }

      case hdrs: Http2HeadersFrame if hdrs.isEndStream =>
        state match {
          case Closed(_) => false

          case state@RemoteClosed() =>
            if (resetRemote(state, Reset.Closed)) true
            else admitRemote(hdrs)

          case Open(remote@RemotePending()) =>
            val q = new AsyncQueue[Frame]
            if (stateRef.compareAndSet(state, new RemoteClosed(q))) {
              remote.setMessage(mkRemoteMsg(hdrs.headers, Stream.empty(q)))
              true
            } else admitRemote(hdrs)

          case Open(remote@RemoteStreaming()) =>
            if (stateRef.compareAndSet(state, remote.toRemoteClosed)) {
              val f = toFrame(hdrs)
              statsReceiver.recordRemoteFrame(f)
              remote.offer(f)
            } else admitRemote(hdrs)

          case LocalClosed(remote@RemotePending()) =>
            if (stateRef.compareAndSet(state, Closed(Reset.NoError))) {
              remote.setMessage(mkRemoteMsg(hdrs.headers, NilStream))
              resetP.setDone()
              true
            } else admitRemote(hdrs)

          case LocalClosed(remote@RemoteStreaming()) =>
            if (stateRef.compareAndSet(state, Closed(Reset.NoError))) {
              val f = toFrame(hdrs)
              if (remote.offer(f)) {
                statsReceiver.recordRemoteFrame(f)
                remote.close()
                resetP.setDone()
                true
              } else false
            } else admitRemote(hdrs)
        }

      case hdrs: Http2HeadersFrame =>
        // A HEADERS frame without END_STREAM may only be received to
        // initiate a message (i.e. when the remote is still pending).
        state match {
          case Closed(_) => false
          case state@RemoteClosed() =>
            if (resetRemote(state, Reset.Closed)) false
            else admitRemote(hdrs)

          case RemoteOpen(remote@RemoteStreaming()) =>
            if (resetRemote(remote, Reset.InternalError)) false
            else admitRemote(hdrs)

          case Open(remote@RemotePending()) =>
            val q = new AsyncQueue[Frame]
            if (stateRef.compareAndSet(state, Open(RemoteStreaming(q)))) {
              remote.setMessage(mkRemoteMsg(hdrs.headers, Stream(q)))
              true
            } else admitRemote(hdrs)

          case LocalClosed(remote@RemotePending()) =>
            val q = new AsyncQueue[Frame]
            if (stateRef.compareAndSet(state, LocalClosed(RemoteStreaming(q)))) {
              remote.setMessage(mkRemoteMsg(hdrs.headers, Stream(q)))
              true
            } else admitRemote(hdrs)
        }

      case data: Http2DataFrame =>
        state match {
          case Closed(_) => false

          case state@RemoteClosed() =>
            if (resetRemote(state, Reset.Closed)) false
            else admitRemote(data)

          case RemoteOpen(remote@RemotePending()) =>
            if (resetRemote(remote, Reset.InternalError)) false
            else admitRemote(data)

          case Open(remote@RemoteStreaming()) =>
            if (data.isEndStream) {
              if (stateRef.compareAndSet(state, remote.toRemoteClosed)) {
                if (admitFrame(toFrame(data), remote)) true
                else throw new IllegalStateException("stream queue closed prematurely")
              } else admitRemote(data)
            } else {
              if (admitFrame(toFrame(data), remote)) true
              else admitRemote(data)
            }

          case LocalClosed(remote@RemoteStreaming()) =>
            if (data.isEndStream) {
              if (stateRef.compareAndSet(state, Closed(Reset.NoError))) {
                if (admitFrame(toFrame(data), remote)) {
                  remote.close()
                  resetP.setDone()
                  true
                } else throw new IllegalStateException("stream queue closed prematurely")
              } else admitRemote(data)
            } else {
              if (admitFrame(toFrame(data), remote)) true
              else admitRemote(data)
            }
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

  /*
   * Writing a LocalMsg to the remote.
   */

  def write(msg: LocalMsg): Future[Future[Unit]] = {
    val headersF = writeHeaders(msg.headers, msg.stream.isEmpty)
    val streamF = headersF.map(_ => writeStream(msg.stream))

    val writeF = streamF.flatten
    onReset.onFailure(writeF.raise(_))
    writeF.onSuccess(closeOnComplete)
    writeF.onFailure(resetOnFailure)

    streamF
  }

  private[this] val closeOnComplete: Unit => Unit = _ => closeLocal()

  private[this] val resetOnFailure: PartialFunction[Throwable, Unit] = {
    case StreamError.Remote(e) =>
      val rst = e match {
        case rst: Reset => rst
        case _ => Reset.Cancel
      }
      log.debug(e, "[%s] remote write failed: %s", prefix, rst)
      remoteReset(rst)

    case StreamError.Local(e) =>
      val rst = e match {
        case rst: Reset => rst
        case _ => Reset.Cancel
      }
      log.debug(e, "[%s] stream read failed: %s", prefix, rst)
      localReset(rst)

    case e =>
      log.error(e, "[%s] unexpected error", prefix)
      localReset(Reset.InternalError)
  }

  private[this] def writeHeaders(hdrs: Headers, eos: Boolean): Future[Unit] =
    stateRef.get match {
      case Closed(rst) => Future.exception(StreamError.Remote(rst))
      case LocalClosed(_) => Future.exception(new IllegalStateException("writing on closed stream"))
      case LocalOpen() =>
        val p = new Promise[Unit]
        p.setInterruptHandler { case e => localReset(Reset.Cancel) }

        transport.write(streamId, hdrs, eos).proxyTo(p)
        p
    }

  /** Write a request stream to the underlying transport */
  private[this] def writeStream(stream: Stream): Future[Unit] = {
    def loop(): Future[Unit] =
      stream.read().rescue(wrapLocalEx)
        .flatMap(writeFrame)
        .before(loop())

    // Create a proxy Future that issues a reset on interrupt or
    // failure.
    val p = new Promise[Unit]
    p.setInterruptHandler { case e => localReset(Reset.Cancel) }

    loop().proxyTo(p)
    p
  }

  private[this] val writeFrame: Frame => Future[Unit] = { frame =>
    stateRef.get match {
      case Closed(rst) => Future.exception(StreamError.Remote(rst))
      case LocalClosed(_) => Future.exception(new IllegalStateException("writing on closed stream"))
      case LocalOpen() =>
        statsReceiver.recordLocalFrame(frame)
        transport.write(streamId, frame).rescue(wrapRemoteEx)
          .before(frame.release().rescue(wrapLocalEx))
    }
  }
}

object Netty4StreamTransport {
  private val log = Logger.get(getClass.getName)

  private val wrapLocalEx: PartialFunction[Throwable, Future[Nothing]] = {
    case e@StreamError.Local(_) => Future.exception(e)
    case e@StreamError.Remote(_) => Future.exception(e)
    case e => Future.exception(StreamError.Local(e))
  }

  private def wrapRemoteEx: PartialFunction[Throwable, Future[Nothing]] = {
    case e@StreamError.Local(_) => Future.exception(e)
    case e@StreamError.Remote(_) => Future.exception(e)
    case e => Future.exception(StreamError.Remote(e))
  }

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

    override protected[this] val prefix =
      s"C L:${transport.localAddress} R:${transport.remoteAddress} S:${streamId}"

    override protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): Response =
      Response(Netty4Message.Headers(headers), stream)
  }

  private class Server(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Response, Request] {

    override protected[this] val prefix =
      s"S L:${transport.localAddress} R:${transport.remoteAddress} S:${streamId}"

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
