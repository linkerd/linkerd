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
private[h2] trait Netty4StreamTransport[LocalMs <: Message, RemoteMsg <: Message] {
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
   *
   * Because remote reads and local writes may occur concurrently,
   * this state is stored in the `stateRef` atomic reference. Writes
   * and reads are performed without locking stateRef (instead, callers )
   */

  private[this] sealed trait StreamState
  private[this] case class Open(remote: RemoteState) extends StreamState
  private[this] case class LocalClosed(remote: RemoteState) extends StreamState
  private[this] object RemoteClosed extends StreamState
  private[this] object Closed extends StreamState

  private[this] sealed trait RemoteState
  private[this] case class RemoteInit(p: Promise[RemoteMsg]) extends RemoteState
  private[this] case class RemoteStreaming(q: AsyncQueue[Frame]) extends RemoteState

  private[this] val _remoteMsgP = new Promise[RemoteMsg]
  def remoteMsg: Future[RemoteMsg] = _remoteMsgP

  // When the remote message is canceled, close the transport, sending
  // a RST_STREAM as appropriate.
  _remoteMsgP.setInterruptHandler {
    case f@Failure(_) if f.isFlagged(Failure.Interrupted) =>
      close(Error.Cancel)
      _remoteMsgP.updateIfEmpty(Throw(Error.ResetException(Error.Cancel))); ()
  }

  private[this] val stateRef: AtomicReference[StreamState] =
    new AtomicReference(Open(RemoteInit(_remoteMsgP)))

  def isClosed = stateRef.get == Closed

  private[this] val closeP = new Promise[Error.StreamError]
  def onClose: Future[Error.StreamError] = closeP

  @tailrec
  final def close(err: Error.StreamError = Error.NoError): Future[Unit] = stateRef.get match {
    case Closed => Future.Unit

    case RemoteClosed =>
      if (stateRef.compareAndSet(RemoteClosed, Closed)) reset(err)
      else close(err)

    case open@Open(RemoteInit(p)) =>
      if (stateRef.compareAndSet(open, Closed)) reset(err)
      else close(err)

    case lc@LocalClosed(RemoteInit(p)) =>
      if (stateRef.compareAndSet(lc, Closed)) reset(err)
      else close(err)

    case open@Open(RemoteStreaming(q)) =>
      if (stateRef.compareAndSet(open, Closed)) {
        q.fail(Error.ResetException(err))
        reset(err)
      } else close(err)

    case lc@LocalClosed(RemoteStreaming(q)) =>
      if (stateRef.compareAndSet(lc, Closed)) {
        q.fail(Error.ResetException(err))
        reset(err)
      } else close(err)
  }

  private[this] def reset(err: Error.StreamError = Error.NoError): Future[Unit] = {
    val f = transport.write(streamId, Frame.Reset(err))
    f.respond {
      case Return(_) =>
        closeP.setValue(err); ()
      case Throw(e) =>
        closeP.setException(e); ()
    }
    f
  }

  protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): RemoteMsg

  /**
   * A Dispatcher reads frames from the remote and offers them into the stream.
   *
   * If an initial Headers frame is offered, the `remote` future is satisfied.
   */
  @tailrec final def offerRemote(in: Http2StreamFrame): Boolean = {
    stateRef.get match {
      case Closed => false

      case RemoteClosed =>
        in match {
          case f: Http2ResetFrame =>
            if (stateRef.compareAndSet(RemoteClosed, Closed)) {
              closeP.setValue(toStreamError(f.errorCode))
              true
            } else offerRemote(in)

          case _ => false
        }

      case open@Open(RemoteInit(msgP)) =>
        in match {
          case f: Http2ResetFrame =>
            if (stateRef.compareAndSet(open, Closed)) {
              val err = toStreamError(f.errorCode)
              msgP.setException(Error.ResetException(err))
              closeP.setValue(err)
              true
            } else offerRemote(in)

          case f: Http2HeadersFrame if f.isEndStream =>
            if (stateRef.compareAndSet(open, RemoteClosed)) {
              val msg = mkRemoteMsg(f.headers, Stream.Nil)
              msgP.setValue(msg)
              true
            } else offerRemote(in)

          case f: Http2HeadersFrame =>
            val outQ = new AsyncQueue[Frame]
            if (stateRef.compareAndSet(open, Open(RemoteStreaming(outQ)))) {
              val msg = mkRemoteMsg(f.headers, Stream(outQ))
              msgP.setValue(msg)
              true
            } else offerRemote(in)

          case f => false
        }

      case lc@LocalClosed(RemoteInit(msgP)) =>
        in match {
          case f: Http2ResetFrame =>
            if (stateRef.compareAndSet(lc, Closed)) {
              val err = toStreamError(f.errorCode)
              msgP.setException(Error.ResetException(err))
              closeP.setValue(err)
              true
            } else offerRemote(in)

          case f: Http2HeadersFrame if f.isEndStream =>
            if (stateRef.compareAndSet(lc, Closed)) {
              val msg = mkRemoteMsg(f.headers, Stream.Nil)
              msgP.setValue(msg)
              closeP.setValue(Error.NoError)
              true
            } else offerRemote(in)

          case f: Http2HeadersFrame =>
            val outQ = new AsyncQueue[Frame]
            if (stateRef.compareAndSet(lc, LocalClosed(RemoteStreaming(outQ)))) {
              val msg = mkRemoteMsg(f.headers, Stream(outQ))
              msgP.setValue(msg)
              true
            } else offerRemote(in)

          case f => false
        }

      case open@Open(RemoteStreaming(outQ)) =>
        toFrame(in) match {
          case out@Frame.Reset(err) =>
            if (stateRef.compareAndSet(open, Closed)) {
              statsReceiver.recordRemoteFrame(out)
              closeP.setValue(err)
              outQ.offer(out)
            } else offerRemote(in)

          case out if out.isEnd =>
            if (stateRef.compareAndSet(open, RemoteClosed)) {
              statsReceiver.recordRemoteFrame(out)
              outQ.offer(out)
            } else offerRemote(in)

          case out =>
            statsReceiver.recordRemoteFrame(out)
            outQ.offer(out)
        }

      case lc@LocalClosed(RemoteStreaming(outQ)) =>
        val out = toFrame(in)
        if (out.isEnd) {
          if (stateRef.compareAndSet(lc, Closed)) {
            statsReceiver.recordRemoteFrame(out)
            closeP.setValue(out match {
              case Frame.Reset(err) => err
              case _ => Error.NoError
            })
            outQ.offer(out)
          } else offerRemote(in)
        } else {
          statsReceiver.recordRemoteFrame(out)
          outQ.offer(out)
        }
    }
  }

  private[this] def toFrame(f: Http2StreamFrame): Frame = f match {
    case f: Http2DataFrame => Netty4Message.Data(f, updateWindow)
    case f: Http2HeadersFrame if f.isEndStream => Netty4Message.Trailers(f.headers)
    case f: Http2ResetFrame => Frame.Reset(toStreamError(f.errorCode))
    case f => throw new IllegalArgumentException(s"invalid stream frame: ${f}")
  }

  private[this] val mapFutureUnit = (_: Any) => Future.Unit

  def write(msg: LocalMs): Future[Future[Unit]] = msg.data match {
    case Stream.Nil =>
      writeHeaders(msg.headers, false).map(mapFutureUnit)
    case data: Stream.Reader =>
      writeHeaders(msg.headers, false).map(_ => writeStream(data))
  }

  @tailrec final def writeHeaders(hdrs: Headers, eos: Boolean = false): Future[Unit] =
    (stateRef.get, eos) match {
      case (Closed | LocalClosed(_), _) =>
        Future.exception(new IllegalStateException("Writing headers on closed stream"))

      case (_, false) =>
        transport.write(streamId, hdrs, false)

      case (open@Open(remote), true) =>
        if (stateRef.compareAndSet(open, LocalClosed(remote))) transport.write(streamId, hdrs, true)
        else writeHeaders(hdrs, true)

      case (RemoteClosed, true) =>
        if (stateRef.compareAndSet(RemoteClosed, Closed)) {
          val f = transport.write(streamId, hdrs, true)
          f.respond {
            case Return(_) =>
              closeP.setValue(Error.NoError); ()
            case Throw(e) =>
              closeP.setException(e); ()
          }
          f
        } else writeHeaders(hdrs, true)
    }

  @tailrec private[this] def _writeFrame(frame: Frame): Future[Boolean] =
    (stateRef.get, frame) match {
      case (Closed | LocalClosed(_), _) =>
        Future.exception(new IllegalStateException("Writing frame on closed stream"))

      case (s0, rst@Frame.Reset(err)) =>
        if (stateRef.compareAndSet(s0, Closed)) {
          statsReceiver.recordLocalFrame(frame)
          closeP.setValue(err)
          transport.write(streamId, rst)
            .before(rst.release())
            .before(Future.True)
        } else _writeFrame(rst)

      case (_, frame) if !frame.isEnd =>
        transport.write(streamId, frame)
          .before(frame.release())
          .before(Future.False)

      case (open@Open(remote), frame) =>
        if (stateRef.compareAndSet(open, LocalClosed(remote))) {
          statsReceiver.recordLocalFrame(frame)
          transport.write(streamId, frame)
            .before(frame.release())
            .before(Future.True)
        } else _writeFrame(frame)

      case (RemoteClosed, frame) =>
        if (stateRef.compareAndSet(RemoteClosed, Closed)) {
          statsReceiver.recordLocalFrame(frame)
          closeP.setValue(Error.NoError)
          transport.write(streamId, frame)
            .before(frame.release())
            .before(Future.True)
        } else _writeFrame(frame)
    }

  private[this] val writeFrame: Frame => Future[Boolean] =
    f => _writeFrame(f)

  /** Write a request stream to the underlying transport */
  def writeStream(reader: Stream.Reader): Future[Unit] = {
    if (reader.isEmpty) Future.Unit
    else {
      lazy val loop: Boolean => Future[Unit] = { eos =>
        if (eos) Future.Unit
        else reader.read().flatMap(writeFrame).flatMap(loop)
      }
      reader.read().flatMap(writeFrame).flatMap(loop)
    }
  }

  private[this] val updateWindow: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}

object Netty4StreamTransport {
  private val log = Logger.get(getClass.getName)

  private def toStreamError(code: Long): Error.StreamError =
    Http2Error.valueOf(code) match {
      case Http2Error.NO_ERROR => Error.NoError
      case Http2Error.INTERNAL_ERROR => Error.InternalError
      case Http2Error.ENHANCE_YOUR_CALM => Error.EnhanceYourCalm
      case Http2Error.REFUSED_STREAM => Error.RefusedStream
      case Http2Error.STREAM_CLOSED => Error.StreamClosed
      case Http2Error.CANCEL => Error.Cancel
      case err => throw new IllegalArgumentException(s"invalid stream error: ${err}")
    }

  class StatsReceiver(underlying: FStatsReceiver) {
    private[this] val local = underlying.scope("local")
    private[this] val localDataBytes = local.stat("data", "bytes")
    private[this] val localTrailersCount = local.counter("trailers")
    private[this] val localResetCount = local.counter("reset")
    val recordLocalFrame: Frame => Unit = {
      case d: Frame.Data => localDataBytes.add(d.buf.length)
      case t: Frame.Trailers => localTrailersCount.incr()
      case Frame.Reset(_) => localResetCount.incr()
    }

    private[this] val remote = underlying.scope("remote")
    private[this] val remoteDataBytes = remote.stat("data", "bytes")
    private[this] val remoteTrailersCount = remote.counter("trailers")
    private[this] val remoteResetCount = remote.counter("reset")
    val recordRemoteFrame: Frame => Unit = {
      case d: Frame.Data => remoteDataBytes.add(d.buf.length)
      case _: Frame.Trailers => remoteTrailersCount.incr()
      case Frame.Reset(_) => remoteResetCount.incr()
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
