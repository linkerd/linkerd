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
 * Reads and writes a bi-directional HTTP/2 stream.
 *
 * Each stream transport has two "sides":
 *
 * - Dispatchers provide a stream with remote frames _from_ a socket
 *   into a `RemoteMsg`-typed message.  The `onRemoteMessage` future
 *   is satisfied when an initial HEADERS frame is received from the
 *   dispatcher.
 *
 * - Dispatchers write a `LocalMsg`-typed message _to_ a socket.  The
 *   stream transport reasds from the message's stream until it
 *   _fails_, so that errors may be propagated if the local side of
 *   the stream is reset.
 *
 * When both sides of the stram are closed, the `onReset` future is
 * satisfied.
 *
 * Either side may reset the stream prematurely, causing the `onReset`
 * future to fail, typically with a [[StreamError]] indicating whether
 * the reset was initiated from the remote or local side of the
 * stream. This information is used by i.e. dispatchers to determine
 * whether a reset frame must be written.
 */
private[h2] trait Netty4StreamTransport[SendMsg <: Message, RecvMsg <: Message] {
  import Netty4StreamTransport._

  /** The HTTP/2 STREAM_ID of this stream. */
  def streamId: Int

  /** for logging */
  protected[this] def prefix: String

  protected[this] def transport: H2Transport.Writer

  protected[this] def statsReceiver: StatsReceiver

  protected[this] def mkRecvMsg(headers: Http2Headers, stream: Stream): RecvMsg

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
   * (Note that SERVER_PUSH is not supported or represented in this
   * version of the state diagram).
   */

  private[this] sealed trait StreamState

  /**
   * The stream is open in both directions.
   *
   * When the StreamTransport is initialized (because a dispatcher has
   * a stream frame it needs to dispatch), it starts in the `Open`
   * state, because the stream exists and neither the remote nor local
   * halves of the stream have been closed (i.e. by sending a frame
   * with END_STREAM set).
   *
   * Since the local half of the stream is written from the dispatcher
   * to the transport, we simply track whether this has completed.
   *
   * The remote half of the connection is represented with by a
   * [[RemoteState]] so that received frames may be passed inbound to
   * the application: first, by satisfying the `onRemoteMessage`
   * Future with [[RemotePending]], and then by offering data and
   * trailer frames to [[RemoteStreaming]].
   */
  private[this] case class Open(remote: RemoteState) extends StreamState with ResettableState {
    /**
     * Act on a stream reset by failing a pending or streaming remote.
     */
    override def reset(rst: Reset): Unit = remote.reset(rst)
  }

  /**
   * The `SendMsg` has been entirely sent, and the `RecvMsg` is still
   * being received.
   */
  private[this] case class LocalClosed(remote: RemoteState)
    extends StreamState with ResettableState {
    override def reset(rst: Reset): Unit = remote.reset(rst)
  }

  /**
   * The `RecvMsg` has been entirely received, and the `SendMsg` is still
   * being sent.
   *
   * Though the remote half is closed, it may reset the local half of
   * the stream.  This is achieved by failing the stream's underlying
   * queue so that the consumer of a stream fails `read()` with a
   * reset.
   */
  private[this] class RemoteClosed(q: AsyncQueue[Frame])
    extends StreamState with ResettableState {
    def close(): Unit = q.fail(Reset.NoError, discard = false)
    override def reset(rst: Reset): Unit = q.fail(rst, discard = true)
  }
  private[this] object RemoteClosed {
    def unapply(rc: RemoteClosed): Boolean = true
  }

  /** Both `RecvMsg` and `SendMsg` have been entirely sent. */
  private[this] case class Closed(error: Reset) extends StreamState

  /** The state of the remote side of a stream. */
  private[this] sealed trait RemoteState extends ResettableState

  /** A remote stream before the initial HEADERS frames have been received. */
  private[this] class RemotePending(p: Promise[RecvMsg]) extends RemoteState {
    def future: Future[RecvMsg] = p
    def setMessage(rm: RecvMsg): Unit = p.setValue(rm)
    override def reset(rst: Reset): Unit = p.setException(rst)
  }
  private[this] object RemotePending {
    def unapply(rs: RemotePending): Boolean = true
  }

  /** A remote stream that has been initiated but not yet closed or reset. */
  private[this] class RemoteStreaming(q: AsyncQueue[Frame]) extends RemoteState {
    def toRemoteClosed: RemoteClosed = new RemoteClosed(q)
    def offer(f: Frame): Boolean = q.offer(f)
    def close(): Unit = q.fail(Reset.NoError, discard = false)
    override def reset(rst: Reset): Unit = q.fail(rst, discard = true)
  }
  private[this] object RemoteStreaming {
    def apply(q: AsyncQueue[Frame]): RemoteStreaming = new RemoteStreaming(q)
    def unapply(rs: RemoteStreaming): Boolean = true
  }

  /** Helper to extract a RemoteState from a StreamState. */
  private[this] object RemoteOpen {
    def unapply(s: StreamState): Option[RemoteState] = s match {
      case Open(r) => Some(r)
      case LocalClosed(r) => Some(r)
      case Closed(_) | RemoteClosed() => None
    }
  }

  /** Helper to match writable states. */
  private[this] object LocalOpen {
    def unapply(s: StreamState): Boolean = s match {
      case Open(_) | RemoteClosed() => true
      case Closed(_) | LocalClosed(_) => false
    }
  }

  /**
   * Because remote reads and local writes may occur concurrently,
   * this state is stored in the `stateRef` atomic reference. Writes
   * and reads are performed without locking (at the expense of
   * retrying on collision).
   */
  private[this] val stateRef: AtomicReference[StreamState] = {
    val remoteMsgP = new Promise[RecvMsg]

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

    new AtomicReference(Open(new RemotePending(remoteMsgP)))
  }

  val onRecvMessage: Future[RecvMsg] = stateRef.get match {
    case Open(rp@RemotePending()) => rp.future
    case s => sys.error(s"unexpected initailzation state: $s")
  }

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

  def remoteReset(err: Reset): Unit =
    if (tryReset(err)) err match {
      case Reset.NoError =>
        resetP.setDone(); ()
      case err => resetP.setException(StreamError.Remote(err))
    }

  def localReset(err: Reset): Unit =
    if (tryReset(err)) err match {
      case Reset.NoError =>
        resetP.setDone(); ()
      case err => resetP.setException(StreamError.Local(err))
    }

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
        if (stateRef.compareAndSet(state, Closed(Reset.InternalError))) {
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

  /**
   * Offer a Netty Http2StreamFrame from the remote.
   *
   * `recv` returns false to indicate that a frame could not be
   * accepted.  This may occur, for example, when a message is
   * received on a closed stream.
   */
  @tailrec final def recv(in: Http2StreamFrame): Boolean = {
    val state = stateRef.get
    log.trace("[%s] admitting %s in %s", prefix, in.name, state)

    def resetFromRemote(remote: ResettableState, rst: Reset): Boolean =
      if (stateRef.compareAndSet(state, Closed(rst))) {
        println(s"REMOTE RESET: $rst")
        remote.reset(rst)
        resetP.setException(StreamError.Remote(rst))
        true
      } else false

    def resetFromLocal(remote: ResettableState, rst: Reset): Boolean =
      if (stateRef.compareAndSet(state, Closed(rst))) {
        println(s"LOCAL RESET: $rst")
        remote.reset(rst)
        resetP.setException(StreamError.Local(rst))
        true
      } else false

    def recvFrame(f: Frame, remote: RemoteStreaming): Boolean =
      if (remote.offer(f)) {
        statsReceiver.recordRemoteFrame(f)
        true
      } else false

    println(s"IN: $in")
    in match {
      case rst: Http2ResetFrame =>
        val err = Netty4Message.Reset.fromFrame(rst)
        state match {
          case Closed(_) => false

          case RemoteOpen(remote) =>
            if (resetFromRemote(remote, err)) {
              statsReceiver.remoteResetCount.incr()
              true
            } else recv(rst)

          case state@RemoteClosed() =>
            if (resetFromRemote(state, err)) {
              statsReceiver.remoteResetCount.incr()
              true
            } else recv(rst)
        }

      case hdrs: Http2HeadersFrame if hdrs.isEndStream =>
        state match {
          case Closed(_) => false

          case state@RemoteClosed() =>
            if (resetFromLocal(state, Reset.InternalError)) true
            else recv(hdrs)

          case Open(remote@RemotePending()) =>
            val q = new AsyncQueue[Frame](1)
            val msg = mkRecvMsg(hdrs.headers, Stream.empty(q))
            if (ConnectionHeaders.detect(msg.headers)) {
              if (resetFromLocal(remote, Reset.ProtocolError)) true
              else recv(hdrs)
            } else {
              if (stateRef.compareAndSet(state, new RemoteClosed(q))) {
                remote.setMessage(msg)
                true
              } else recv(hdrs)
            }

          case Open(remote@RemoteStreaming()) =>
            if (stateRef.compareAndSet(state, remote.toRemoteClosed)) {
              val f = toFrame(hdrs)
              statsReceiver.recordRemoteFrame(f)
              remote.offer(f)
            } else recv(hdrs)

          case state@LocalClosed(remote@RemotePending()) =>
            val msg = mkRecvMsg(hdrs.headers, NilStream)
            if (ConnectionHeaders.detect(msg.headers)) {
              if (resetFromLocal(state, Reset.ProtocolError)) true
              else recv(hdrs)
            } else {
              if (stateRef.compareAndSet(state, Closed(Reset.NoError))) {
                remote.setMessage(msg)
                resetP.setDone()
                true
              } else recv(hdrs)
            }

          case LocalClosed(remote@RemoteStreaming()) =>
            if (stateRef.compareAndSet(state, Closed(Reset.NoError))) {
              val f = toFrame(hdrs)
              if (remote.offer(f)) {
                statsReceiver.recordRemoteFrame(f)
                remote.close()
                resetP.setDone()
                true
              } else false
            } else recv(hdrs)
        }

      case hdrs: Http2HeadersFrame =>
        // A HEADERS frame without END_STREAM may only be received to
        // initiate a message (i.e. when the remote is still pending).
        state match {
          case Closed(_) => false
          case state@RemoteClosed() =>
            if (resetFromLocal(state, Reset.Closed)) false
            else recv(hdrs)

          case RemoteOpen(remote@RemoteStreaming()) =>
            if (resetFromLocal(remote, Reset.InternalError)) false
            else recv(hdrs)

          case Open(remote@RemotePending()) =>
            val q = new AsyncQueue[Frame]
            val msg = mkRecvMsg(hdrs.headers, Stream(q))
            if (ConnectionHeaders.detect(msg.headers)) {
              if (resetFromLocal(remote, Reset.ProtocolError)) true
              else recv(hdrs)
            } else {
              if (stateRef.compareAndSet(state, Open(RemoteStreaming(q)))) {
                remote.setMessage(msg)
                true
              } else recv(hdrs)
            }

          case LocalClosed(remote@RemotePending()) =>
            val q = new AsyncQueue[Frame]
            val msg = mkRecvMsg(hdrs.headers, Stream(q))
            if (ConnectionHeaders.detect(msg.headers)) {
              if (resetFromLocal(remote, Reset.ProtocolError)) true
              else recv(hdrs)
            } else {
              if (stateRef.compareAndSet(state, LocalClosed(RemoteStreaming(q)))) {
                remote.setMessage(msg)
                true
              } else recv(hdrs)
            }
        }

      case data: Http2DataFrame =>
        state match {
          case Closed(_) => false

          case state@RemoteClosed() =>
            if (resetFromLocal(state, Reset.Closed)) false
            else recv(data)

          case RemoteOpen(remote@RemotePending()) =>
            if (resetFromLocal(remote, Reset.InternalError)) false
            else recv(data)

          case Open(remote@RemoteStreaming()) =>
            if (data.isEndStream) {
              if (stateRef.compareAndSet(state, remote.toRemoteClosed)) {
                if (recvFrame(toFrame(data), remote)) true
                else throw new IllegalStateException("stream queue closed prematurely")
              } else recv(data)
            } else {
              if (recvFrame(toFrame(data), remote)) true
              else recv(data)
            }

          case LocalClosed(remote@RemoteStreaming()) =>
            if (data.isEndStream) {
              if (stateRef.compareAndSet(state, Closed(Reset.NoError))) {
                if (recvFrame(toFrame(data), remote)) {
                  remote.close()
                  resetP.setDone()
                  true
                } else throw new IllegalStateException("stream queue closed prematurely")
              } else recv(data)
            } else {
              if (recvFrame(toFrame(data), remote)) true
              else recv(data)
            }
        }
    }
  }

  private[this] def toFrame(f: Http2StreamFrame): Frame = f match {
    case f: Http2DataFrame => Netty4Message.Data(f, updateWindow)
    case f: Http2HeadersFrame if f.isEndStream => Netty4Message.Trailers(f.headers)
    case f => throw new IllegalArgumentException(s"invalid stream frame: ${f}")
  }

  private[this] val updateWindow: Int => Future[Unit] = transport.updateWindow(streamId, _)

  /**
   * Write a `SendMsg`-typed [[Message]] to the remote.
   *
   * The outer future is satisfied initially to indicate that the
   * local message has been initiated (i.e. its HEADERS have been
   * sent). This first future is satisfied with a second future. The
   * second future is satisfied when the full local stream has been
   * written to the remote.
   *
   * If any write fails or is canceled, the entire stream is reset.
   *
   * If the stream is reset, writes are canceled.
   */
  def send(msg: SendMsg): Future[Future[Unit]] = {
    val headersF = writeHeaders(msg.headers, msg.stream.isEmpty)
    val streamFF = headersF.map(_ => writeStream(msg.stream))

    val writeF = streamFF.flatten
    onReset.onFailure(writeF.raise(_))
    writeF.respond {
      case Return(_) =>
        closeLocal()

      case Throw(StreamError.Remote(e)) =>
        val rst = e match {
          case rst: Reset => rst
          case _ => Reset.Cancel
        }
        log.debug(e, "[%s] remote write failed: %s", prefix, rst)
        remoteReset(rst)

      case Throw(StreamError.Local(e)) =>
        val rst = e match {
          case rst: Reset => rst
          case _ => Reset.Cancel
        }
        log.debug(e, "[%s] stream read failed: %s", prefix, rst)
        localReset(rst)

      case Throw(e) =>
        log.error(e, "[%s] unexpected error", prefix)
        localReset(Reset.InternalError)
    }

    streamFF
  }

  private[this] def writeHeaders(hdrs: Headers, eos: Boolean): Future[Unit] =
    stateRef.get match {
      case Closed(rst) => Future.exception(StreamError.Remote(rst))
      case LocalClosed(_) => Future.exception(new IllegalStateException("writing on closed stream"))
      case LocalOpen() => localResetOnCancel(transport.write(streamId, hdrs, eos))
    }

  /** Write a request stream to the underlying transport */
  private[this] def writeStream(stream: Stream): Future[Unit] = {
    def loop(): Future[Unit] =
      stream.read().rescue(wrapLocalEx)
        .flatMap(writeFrame)
        .before(loop())

    localResetOnCancel(loop())
  }

  private[this] def localResetOnCancel[T](f: Future[T]): Future[T] = {
    val p = new Promise[T]
    p.setInterruptHandler {
      case e =>
        localReset(Reset.Cancel)
        f.raise(e)
    }
    f.proxyTo(p)
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
  private lazy val log = Logger.get(getClass.getName)

  /** Helper: a state that supports Reset.  (All but Closed) */
  private trait ResettableState {
    def reset(rst: Reset): Unit
  }

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

    override protected[this] def mkRecvMsg(headers: Http2Headers, stream: Stream): Response =
      Response(Netty4Message.Headers(headers), stream)
  }

  private class Server(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Response, Request] {

    override protected[this] val prefix =
      s"S L:${transport.localAddress} R:${transport.remoteAddress} S:${streamId}"

    override protected[this] def mkRecvMsg(headers: Http2Headers, stream: Stream): Request =
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
