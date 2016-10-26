package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Failure
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.AtomicReference
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
  import Netty4StreamTransport.log

  /** The HTTP/2 STREAM_ID of this stream. */
  def streamId: Int

  protected[this] def transport: H2Transport.Writer
  protected[this] def statsReceiver: StatsReceiver

  @volatile private[this] var isRemoteClosed, isLocalClosed = false
  def isClosed = isRemoteClosed && isLocalClosed

  private[this] val closeP = new Promise[Unit]
  def onClose: Future[Unit] = closeP

  private[this] def setRemoteClosed(): Unit = {
    isRemoteClosed = true
    if (isClosed) { closeP.setDone(); () }
  }

  private[this] def setLocalClosed(): Unit = {
    isLocalClosed = true
    if (isClosed) { closeP.setDone(); () }
  }

  def close(): Future[Unit] =
    if (isClosed) Future.Unit
    else {
      setRemoteClosed()
      setLocalClosed()
      transport.resetNoError(streamId)
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
        case f: Http2HeadersFrame if f.isEndStream =>
          if (remoteState.compareAndSet(s0, RemoteClosed)) {
            p.setValue(mkRemoteMsg(f.headers, Stream.Nil))
            setRemoteClosed()
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
          setRemoteClosed()
          outQ.offer(out)
        } else offerRemote(in)
      } else outQ.offer(out)

    case RemoteClosed => false
  }

  private[this] def toFrame(f: Http2StreamFrame): Frame = f match {
    case f: Http2DataFrame => Netty4Message.Data(f, updateWindow)
    case f: Http2HeadersFrame if f.isEndStream => Netty4Message.Trailers(f.headers)
    case f => throw new IllegalArgumentException(s"invalid stream frame: ${f}")
  }

  private[this] val mapFutureUnit = (_: Any) => Future.Unit

  def write(msg: LocalMs): Future[Future[Unit]] = msg.data match {
    case Stream.Nil =>
      writeHeaders(msg.headers, false).map(mapFutureUnit)
    case data: Stream.Reader =>
      writeHeaders(msg.headers, false).map { _ => writeStream(data) }
  }

  def writeHeaders(hdrs: Headers, eos: Boolean = false): Future[Unit] = {
    val tx = transport.write(streamId, hdrs, eos)
    if (eos) tx.ensure(setLocalClosed())
    tx
  }

  /** Write a request stream to the underlying transport */
  def writeStream(reader: Stream.Reader): Future[Unit] = {
    require(!isLocalClosed)
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
    val writeF = f match {
      case data: Frame.Data =>
        transport.write(streamId, data)
          .before(data.release())
          .map(_ => data.isEnd)

      case tlrs: Frame.Trailers =>
        transport.write(streamId, tlrs)
          .before(tlrs.release())
          .before(Future.True)
    }
    if (f.isEnd) writeF.ensure(setLocalClosed())
    writeF
  }

  protected[this] def prefix: String

  private[this] val updateWindow: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}

object Netty4StreamTransport {
  private val log = Logger.get(getClass.getName)

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
    override protected[this] val statsReceiver: StatsReceiver = NullStatsReceiver
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
