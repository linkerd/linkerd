package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.stats.{NullStatsReceiver => FNullStatsReceiver, StatsReceiver => FStatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Throw}
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

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
 *   stream transport reads from the message's stream until it is
 *   complete.
 *
 * When both sides of the stream are closed, the `onReset` future is
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
   *
   * Each side of the stream may be in Pending, Streaming, or Closed
   * state.  Pending means that the HEADERS frame has not yet been
   * sent/received.  Streaming means that the HEADERS frame has been
   * sent/received but the side is still open.  Closed means that the
   * side has either sent/received an EOS frame or the stream has been
   * reset.
   */

  private[this] sealed trait SendState
  private[this] case object SendPending extends SendState
  private[this] case class SendStreaming(sendMsg: SendMsg) extends SendState
  private[this] case object SendClosed extends SendState

  private[this] sealed trait RecvState
  private[this] case object RecvPending extends RecvState
  private[this] case class RecvStreaming(recvQ: AsyncQueue[Frame]) extends RecvState
  private[this] case object RecvClosed extends RecvState

  private[this] case class StreamState(send: SendState, recv: RecvState) {
    def toNettyH2State: Http2Stream.State = this match {
      case StreamState(SendClosed, RecvClosed) => Http2Stream.State.CLOSED
      case StreamState(SendClosed, _) => Http2Stream.State.HALF_CLOSED_LOCAL
      case StreamState(_, RecvClosed) => Http2Stream.State.HALF_CLOSED_REMOTE
      case StreamState(_, _) => Http2Stream.State.OPEN
    }
  }

  private[this] val recvMsg = new Promise[RecvMsg] with Promise.InterruptHandler {
    /*
     * If onRecvMsg is interrupted, it indicates that the RecvMsg is
     * not wanted and so we reset the stream.  This is the way that
     * clients will typically signal cancellation if the response
     * Future is not yet satisfied (ie the client stream is in the
     * RecvPending state).
     */
    override protected def onInterrupt(t: Throwable): Unit = t match {
      case rst: Reset =>
        reset(rst, local = true); ()
      case _ => reset(Reset.Cancel, local = true); ()
    }
  }

  private[this] val stateRef: AtomicReference[StreamState] =
    new AtomicReference(StreamState(SendPending, RecvPending))

  val onRecvMessage: Future[RecvMsg] = recvMsg

  private[this] def getNettyH2State: Http2Stream.State = stateRef.get().toNettyH2State
  private[this] def frameStream: H2FrameStream = H2FrameStream(streamId, getNettyH2State)

  /**
   * Satisfied successfully when the stream is fully closed with no
   * error.  Satisfied with a StreamError exception if the stream is
   * reset.  The type of StreamError indicates if the reset originated
   * from the local or remote and therefore indicates if a reset frame
   * should be sent to the remote.
   */
  def onReset: Future[Unit] = resetP
  private[this] val resetP = new Promise[Unit]

  def isClosed: Boolean = onReset.isDefined

  /**
   * Reset the stream by doing the following:
   * - Change the state to fully closed
   * - Fail the sendMsg and recvMsg promises if they are pending
   * - Cancel the sendMsg stream if it is available
   * - Fail the recvMsg stream if it is available
   * - Fail the onReset Future with a StreamError that indicates if
   *   this is a reset from the local or remote
   *
   * @return True if we were able to reset the stream, false if the
   *         stream was already closed.
   */
  def reset(err: Reset, local: Boolean): Boolean = {
    def resetSend(): Unit = {
      stateRef.get match {
        case state@StreamState(SendPending, recvState) =>
          if (stateRef.compareAndSet(state, StreamState(SendClosed, recvState))) {
            resetSend()
          }
        case state@StreamState(SendStreaming(msg), recvState) =>
          if (stateRef.compareAndSet(state, StreamState(SendClosed, recvState))) {
            msg.stream.cancel(err)
          } else {
            resetSend()
          }
        case StreamState(SendClosed, recvState) => // Send side is already closed, nothing to do.
      }
    }

    def resetRecv(): Unit = {
      stateRef.get match {
        case state@StreamState(sendState, RecvPending) =>
          if (stateRef.compareAndSet(state, StreamState(sendState, RecvClosed))) {
            recvMsg.setException(err)
          } else {
            resetRecv()
          }
        case state@StreamState(sendState, RecvStreaming(q)) =>
          if (stateRef.compareAndSet(state, StreamState(sendState, RecvClosed))) {
            Stream.failAndDrainFrameQueue(q, err)
          } else {
            resetRecv()
          }
        case StreamState(sendState, RecvClosed) => // Recv side is already closed, nothing to do.
      }
    }

    val streamError = if (local) {
      statsReceiver.localResetCount.incr()
      StreamError.Local(err)
    } else {
      statsReceiver.remoteResetCount.incr()
      StreamError.Remote(err)
    }
    val didReset = resetP.updateIfEmpty(Throw(streamError))

    resetSend()
    resetRecv()

    if (didReset) {
      log.debug("[%s] stream reset from %s", prefix, if (local) "local" else "remote")
    }
    didReset
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

    def recvFrame(f: Frame, q: AsyncQueue[Frame]): Boolean =
      if (q.offer(f)) {
        statsReceiver.recordRemoteFrame(f)
        true
      } else {
        f.release()
        log.debug("[%s] failed to accept frame %s", prefix, in.name)
        false
      }

    in match {
      /*
       * RESET FRAME
       */
      case rst: Http2ResetFrame =>
        val err = Netty4Message.Reset.fromFrame(rst)
        if (reset(err, local = false)) {
          statsReceiver.remoteResetCount.incr()
          true
        } else false

      /*
       * HEADERS FRAME with EOS (TRAILERS)
       */
      case hdrs: Http2HeadersFrame if hdrs.isEndStream =>
        //Such a Frame may be received while in RecvPending (headers
        // only message) or while in RecvStreaming (trailers).
        state match {
          // Pending
          case StreamState(sendState, RecvPending) =>
            val stream = Stream.empty()
            val msg = mkRecvMsg(hdrs.headers, stream)
            if (ConnectionHeaders.detect(msg.headers)) {
              log.debug("[%s] illegal connection headers detected in %s", prefix, msg.headers)
              reset(Reset.ProtocolError, local = true)
              false
            } else {
              if (stateRef.compareAndSet(state, StreamState(sendState, RecvClosed))) {
                stream.onCancel.onSuccess { rst =>
                  // If the recvMsg stream is cancelled, reset the stream.
                  reset(rst, local = true); ()
                }
                recvMsg.setValue(msg)
                if (sendState == SendClosed) {
                  resetP.setDone()
                }
                true
              } else recv(hdrs)
            }

          // Streaming
          case StreamState(sendState, RecvStreaming(q)) =>
            if (stateRef.compareAndSet(state, StreamState(sendState, RecvClosed))) {
              if (sendState == SendClosed) {
                resetP.setDone()
              }
              recvFrame(toFrame(hdrs), q)
            } else recv(hdrs)

          // Closed
          case StreamState(sendState, RecvClosed) =>
            log.debug("[%s] frame received while in RecvClosed", prefix)
            reset(Reset.InternalError, local = true)
            false
        }

      /*
       * HEADERS FRAME
       */
      case hdrs: Http2HeadersFrame =>
        // A HEADERS frame without END_STREAM may only be received to
        // initiate a message (i.e. when the remote is still pending).
        state match {
          // Pending
          case StreamState(sendState, RecvPending) =>
            val q = new AsyncQueue[Frame]
            val stream = Stream(q)
            val msg = mkRecvMsg(hdrs.headers, stream)
            if (ConnectionHeaders.detect(msg.headers)) {
              log.debug("[%s] illegal connection headers in recv message: %s", prefix, msg.headers)
              reset(Reset.ProtocolError, local = true)
              false
            } else {
              if (stateRef.compareAndSet(state, StreamState(sendState, RecvStreaming(q)))) {
                stream.onCancel.onSuccess { rst =>
                  // If the recvMsg stream is cancelled, reset the stream.
                  reset(rst, local = true); ()
                }
                recvMsg.setValue(msg)
                true
              } else recv(hdrs)
            }

          // Streaming
          case StreamState(sendState, RecvStreaming(_)) =>
            log.debug("[%s] headers frame with eos=false received while in RecvStreaming", prefix)
            reset(Reset.InternalError, local = true)
            false

          // Closed
          case StreamState(sendState, RecvClosed) =>
            log.debug("[%s] frame received while in RecvClosed", prefix)
            reset(Reset.InternalError, local = true)
            false
        }

      /*
       * DATA FRAME with EOS
       */
      case data: Http2DataFrame if data.isEndStream =>
        state match {
          // Pending
          case StreamState(sendState, RecvPending) =>
            log.debug("[%s] data frame received while in RecvPending", prefix)
            reset(Reset.InternalError, local = true)
            false

          // Streaming
          case StreamState(sendState, RecvStreaming(q)) =>
            if (stateRef.compareAndSet(state, StreamState(sendState, RecvClosed))) {
              if (sendState == SendClosed) {
                resetP.setDone()
              }
              recvFrame(toFrame(data), q)
            } else recv(data)

          // Closed
          case StreamState(sendState, RecvClosed) =>
            log.debug("[%s] frame received while in RecvClosed", prefix)
            reset(Reset.InternalError, local = true)
            false
        }

      /*
       * DATA FRAME
       */
      case data: Http2DataFrame =>
        state match {
          // Pending
          case StreamState(sendState, RecvPending) =>
            log.debug("[%s] data frame received while in RecvPending", prefix)
            reset(Reset.InternalError, local = true)
            false

          // Streaming
          case StreamState(sendState, RecvStreaming(q)) =>
            recvFrame(toFrame(data), q)

          // Closed
          case StreamState(sendState, RecvClosed) =>
            log.debug("[%s] frame received while in RecvClosed", prefix)
            reset(Reset.InternalError, local = true)
            false
        }
    }
  }

  private[this] def toFrame(f: Http2StreamFrame): Frame = f match {
    case f: Http2DataFrame => Netty4Message.Data(f, updateWindow)
    case f: Http2HeadersFrame if f.isEndStream => Netty4Message.Trailers(f.headers)
    case _ => throw new IllegalArgumentException(s"invalid stream frame: $f")
  }

  private[this] val updateWindow: Int => Future[Unit] = transport.updateWindow(frameStream, _)

  /**
   * Write a `SendMsg`-typed [[Message]] to the remote.
   *
   * The outer future is satisfied initially to indicate that the
   * local message has been initiated (i.e. its HEADERS have been
   * sent). This first future is satisfied with a second future. The
   * second future is satisfied when the full local stream has been
   * written to the remote.
   *
   * If any write fails or is interrupted, the entire stream is reset.
   */
  def send(msg: SendMsg): Future[Future[Unit]] = {

    val headersF = writeHeaders(msg).onFailure {
      // If writeHeaders fails, the state will be left in SendPending
      // so we must cancel the send stream ourselves.
      case rst: Reset => msg.stream.cancel(rst)
      case e: Throwable => msg.stream.cancel(Reset.InternalError)
    }
    val streamFF = headersF.map { _ =>
      if (msg.stream.isEmpty) Future.Unit
      else localResetOnCancel(writeStream(msg.stream))
    }

    val writeF = streamFF.flatten
    onReset.onFailure { e =>
      // If the stream is reset, interrupt the write.
      writeF.raise(e)
    }
    writeF.onFailure {
      case rst: Reset =>
        log.debug(rst, "[%s] writing message failed", prefix)
        reset(rst, local = true); ()
      case e: Throwable =>
        log.debug(e, "[%s] writing message failed", prefix)
        reset(Reset.InternalError, local = true); ()
    }

    localResetOnCancel(streamFF)
  }

  private[this] val writeHeaders: SendMsg => Future[Unit] = { msg =>
    stateRef.get match {
      // Pending (Header-only message)
      case state@StreamState(SendPending, recvState) if msg.stream.isEmpty =>
        if (stateRef.compareAndSet(state, StreamState(SendClosed, recvState))) {
          if (recvState == RecvClosed) {
            resetP.setDone()
          }
          if (ConnectionHeaders.detect(msg.headers)) {
            log.debug("[%s] illegal connection headers in send message: %s", prefix, msg.headers)
            Future.exception(Reset.ProtocolError)
          } else {
            transport.write(frameStream, msg.headers, eos = true)
          }
        } else {
          writeHeaders(msg)
        }

      // Pending
      case state@StreamState(SendPending, recvState) =>
        if (stateRef.compareAndSet(state, StreamState(SendStreaming(msg), recvState))) {
          if (ConnectionHeaders.detect(msg.headers)) {
            log.debug("[%s] illegal connection headers in send message: %s", prefix, msg.headers)
            Future.exception(Reset.ProtocolError)
          } else {
            transport.write(frameStream, msg.headers, eos = false)
          }
        } else {
          writeHeaders(msg)
        }

      // Streaming
      case state@StreamState(SendStreaming(_), recvState) =>
        log.debug("[%s] cannot write initial headers frame while in SendStreaming", prefix)
        Future.exception(Reset.ProtocolError)

      // Closed
      case state@StreamState(SendClosed, recvState) =>
        log.debug("[%s] cannot write initial headers frame while in SendClosed", prefix)
        Future.exception(Reset.ProtocolError)
    }
  }

  /** Write a request stream to the underlying transport */
  private[this] val writeStream: Stream => Future[Unit] = { stream =>
    def loop(): Future[Unit] = {
      stream.read().flatMap { f =>
        writeFrame(f).flatMap { _ =>
          if (!f.isEnd) loop() else Future.Unit
        }
      }
    }

    loop()
  }

  private[this] def localResetOnCancel[T](f: Future[T]): Future[T] = {
    val p = new Promise[T] with Promise.InterruptHandler {
      override protected def onInterrupt(t: Throwable): Unit = {
        t match {
          case rst: Reset =>
            log.debug(rst, "[%s] writing message was interrupted", prefix)
            reset(rst, local = true)
          case _ =>
            log.debug(t, "[%s] writing message was interrupted", prefix)
            reset(Reset.Cancel, local = true)
        }
        f.raise(t)
      }
    }
    f.proxyTo(p)
    p
  }

  private[this] val writeFrame: Frame => Future[Unit] = { frame =>
    stateRef.get match {
      // Pending
      case state@StreamState(SendPending, recvState) =>
        frame.release()
        log.debug("[%s] cannot write body frame while in SendPending")
        reset(Reset.ProtocolError, local = true)
        Future.exception(Reset.ProtocolError)

      // Streaming (EOS)
      case state@StreamState(SendStreaming(_), recvState) if frame.isEnd =>
        if (stateRef.compareAndSet(state, StreamState(SendClosed, recvState))) {
          statsReceiver.recordLocalFrame(frame)
          if (recvState == RecvClosed) {
            resetP.setDone()
          }
          transport.write(frameStream, frame).transform { _ =>
            frame.release()
          }
        } else {
          writeFrame(frame)
        }

      // Streaming
      case state@StreamState(SendStreaming(_), recvState) =>
        statsReceiver.recordLocalFrame(frame)
        transport.write(frameStream, frame).transform { _ =>
          frame.release()
        }

      // Closed
      case state@StreamState(SendClosed, recvState) =>
        frame.release()
        log.debug("[%s] cannot write body frame while in SendClosed")
        reset(Reset.ProtocolError, local = true)
        Future.exception(Reset.ProtocolError)
    }
  }
}

object Netty4StreamTransport {
  private lazy val log = Logger.get("h2")

  class StatsReceiver(val underlying: FStatsReceiver) {
    private[this] val local = underlying.scope("local")
    private[this] val localDataBytes = local.stat("data", "bytes")
    private[this] val localDataFrames = local.counter("data", "frames")
    private[this] val localTrailersCount = local.counter("trailers")
    val localResetCount = local.counter("reset")
    val recordLocalFrame: Frame => Unit = {
      case d: Frame.Data =>
        localDataFrames.incr()
        localDataBytes.add(d.buf.readableBytes())
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
        remoteDataBytes.add(d.buf.readableBytes())
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
