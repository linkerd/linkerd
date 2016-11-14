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

  private[this] sealed trait StreamState
  private[this] object StreamState {
    object Open extends StreamState
    object RemoteClosed extends StreamState
    object LocalClosed extends StreamState
    object Closed extends StreamState
  }
  private[this] val state = new AtomicReference[StreamState](StreamState.Open)

  def isClosed = state.get == StreamState.Closed

  private[this] val closeP = new Promise[Unit]
  def onClose: Future[Unit] = closeP

  @tailrec
  private[this] def setRemoteClosed(result: Try[Unit]): Unit = state.get match {
    case StreamState.Closed | StreamState.RemoteClosed =>
    case StreamState.LocalClosed =>
      if (state.compareAndSet(StreamState.LocalClosed, StreamState.Closed)) {
        closeP.updateIfEmpty(result); ()
      } else setRemoteClosed(result)
    case StreamState.Open =>
      if (!state.compareAndSet(StreamState.Open, StreamState.RemoteClosed)) {
        setRemoteClosed(result)
      }
  }

  @tailrec
  private[this] def setLocalClosed(result: Try[Unit]): Unit = state.get match {
    case StreamState.Closed | StreamState.LocalClosed =>
    case StreamState.RemoteClosed =>
      if (!state.compareAndSet(StreamState.RemoteClosed, StreamState.Closed)) setLocalClosed(result)
      else { closeP.updateIfEmpty(result); () }
    case StreamState.Open =>
      if (!state.compareAndSet(StreamState.Open, StreamState.LocalClosed)) setLocalClosed(result)
  }

  @tailrec
  private[this] def setClosed(result: Try[Unit]): Boolean = state.get match {
    case StreamState.Closed => false
    case orig =>
      if (!state.compareAndSet(orig, StreamState.Closed)) setClosed(result)
      else { closeP.updateIfEmpty(result); true }
  }

  @tailrec
  final def close(): Future[Unit] = state.get match {
    case StreamState.Closed => Future.Unit
    case orig =>
      if (!state.compareAndSet(orig, StreamState.Closed)) close()
      else transport.write(streamId, Frame.Reset(Error.NoError))
  }

  protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): RemoteMsg

  private[this] sealed trait RemoteState
  private[this] case class RemoteInit(p: Promise[RemoteMsg]) extends RemoteState
  private[this] case class RemoteStreaming(q: AsyncQueue[Frame]) extends RemoteState
  private[this] object RemoteClosed extends RemoteState

  private[this] val _remoteMsgP = new Promise[RemoteMsg]
  def remoteMsg: Future[RemoteMsg] = _remoteMsgP
  private[this] val remoteState: AtomicReference[RemoteState] =
    new AtomicReference(RemoteInit(_remoteMsgP))

  /**
   * A Dispatcher reads frames from the remote and offers them into the stream.
   *
   * If an initial Headers frame is offered, the `remote` future is satisfied.
   */
  @tailrec final def offerRemote(in: Http2StreamFrame): Boolean = remoteState.get match {
    case s0@RemoteInit(p) =>
      in match {
        case f: Http2ResetFrame =>
          val e = Error.ResetException(toStreamError(f.errorCode))
          p.setException(e)
          q.fail(e, discard = true)
          setRemoteClosed(e)
          setLocalClosed(e)
          true

        case f: Http2HeadersFrame if f.isEndStream =>
          if (remoteState.compareAndSet(s0, RemoteClosed)) {
            p.setValue(mkRemoteMsg(f.headers, Stream.Nil))
            setRemoteClosed(Return.Unit)
            true
          } else offerRemote(in)

        case f: Http2HeadersFrame =>
          val outQ = new AsyncQueue[Frame]
          if (remoteState.compareAndSet(s0, RemoteStreaming(outQ))) {
            val msg = mkRemoteMsg(f.headers, Stream(outQ))
            p.setValue(msg)
            true
          } else offerRemote(in)

        case f => false
      }

    case s0@RemoteStreaming(outQ) =>
      val out = toFrame(in)
      if (out.isEnd) {
        if (remoteState.compareAndSet(s0, RemoteClosed)) {
          statsReceiver.recordRemoteFrame(out)
          setRemoteClosed(out match {
            case Frame.Reset(err) => Throw(Error.ResetException(err))
            case _ => Return.Unit
          })
          outQ.offer(out)
        } else offerRemote(in)
      } else {
        statsReceiver.recordRemoteFrame(out)
        outQ.offer(out)
      }

    case RemoteClosed => false
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

  def writeHeaders(hdrs: Headers, eos: Boolean = false): Future[Unit] = {
    val tx = transport.write(streamId, hdrs, eos)
    if (eos) tx.ensure(setLocalClosed(Return.Unit))
    tx
  }

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

  private[this] val writeFrame: Frame => Future[Boolean] = { f =>
    statsReceiver.recordLocalFrame(f)
    val writeF = transport.write(streamId, f).before(f.release())
    if (f.isEnd) {
      val result = f match {
        case Frame.Reset(err) => Throw(Error.ResetException(err))
        case _ => Return.Unit
      }
      writeF.ensure(setLocalClosed(result))
    }
    writeF.map(_ => f.isEnd)
  }

  protected[this] def prefix: String

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

    override protected[this] def prefix: String = s"client: stream $streamId"

    override protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): Response =
      Response(Netty4Message.Headers(headers), stream)
  }

  private class Server(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Response, Request] {

    override protected[this] def prefix: String = s"server: stream $streamId"

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
