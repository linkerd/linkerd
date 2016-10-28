package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.handler.codec.http2._

/**
 * Models a single HTTP/2 stream.
 *
 * Transports send a `Local`-typed message via an underlying
 * [[H2Transport.Writer]]. A dispatcher, which models a single HTTP/2
 * connection, provides the transport with `Http2StreamFrame`
 * instances that are used to build a `Remote`-typed message.
 */
private[h2] trait Netty4StreamTransport[Local <: Message, Remote <: Message] {
  import Netty4StreamTransport.log

  /** The HTTP/2 STREAM_ID of this stream. */
  def streamId: Int

  protected[this] def transport: H2Transport.Writer
  protected[this] def minAccumFrames: Int
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

  private[this] val remoteP = new Promise[Remote]
  def remote: Future[Remote] = remoteP

  /**
   * Supports writing frames _FROM_ the remote transport to the `remote` stream.
   */
  private[this] var remoteWriter: Stream.Writer[Http2StreamFrame] = null

  protected[this] def mkRemote(headers: Http2Headers, stream: Stream): Remote

  /**
   * A Dispatcher reads frames from the remote and offers them into the stream.
   *
   * If an initial Headers frame is offered, the `remote` future is satisfied.
   */
  def offerRemote(frame: Http2StreamFrame): Boolean =
    if (isRemoteClosed) false
    else synchronized {
      remoteWriter match {
        case null =>
          frame match {
            case frame: Http2HeadersFrame =>
              val stream =
                if (frame.isEndStream) {
                  setRemoteClosed()
                  Stream.Nil
                } else {
                  val stream = newStream()
                  stream.onEnd.ensure(setRemoteClosed())
                  remoteWriter = stream
                  stream
                }
              remoteP.setValue(mkRemote(frame.headers, stream))
              true

            case _ => false
          }

        case stream => stream.write(frame)
      }
    }

  private[this] val mapFutureUnit = (_: Any) => Future.Unit

  def write(msg: Local): Future[Future[Unit]] = msg.data match {
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

  private[this] val writeFrame: Frame => Future[Boolean] = { v =>
    val writeF = v match {
      case data: Frame.Data =>
        transport.write(streamId, data)
          .before(data.release())
          .map(_ => data.isEnd)

      case tlrs: Frame.Trailers =>
        transport.write(streamId, tlrs)
          .before(Future.True)
    }
    if (v.isEnd) writeF.ensure(setLocalClosed())
    writeF
  }

  protected[this] def prefix: String

  private[this] def newStream(): Stream.Reader with Stream.Writer[Http2StreamFrame] =
    new Netty4Stream(releaser, minAccumFrames, statsReceiver)

  private[this] val releaser: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}

object Netty4StreamTransport {
  private val log = Logger.get(getClass.getName)

  private class Client(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val minAccumFrames: Int,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Request, Response] {

    override protected[this] def prefix: String = s"client: stream $streamId"

    override protected[this] def mkRemote(headers: Http2Headers, stream: Stream): Response =
      Response(Netty4Message.Headers(headers), stream)
  }

  private class Server(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val minAccumFrames: Int,
    override protected[this] val statsReceiver: StatsReceiver = NullStatsReceiver
  ) extends Netty4StreamTransport[Response, Request] {

    override protected[this] def prefix: String = s"server: stream $streamId"

    override protected[this] def mkRemote(headers: Http2Headers, stream: Stream): Request =
      Request(Netty4Message.Headers(headers), stream)
  }

  def client(
    id: Int,
    writer: H2Transport.Writer,
    minAccumFrames: Int,
    stats: StatsReceiver = NullStatsReceiver
  ): Netty4StreamTransport[Request, Response] =
    new Client(id, writer, minAccumFrames, stats)

  def server(
    id: Int,
    writer: H2Transport.Writer,
    minAccumFrames: Int,
    stats: StatsReceiver = NullStatsReceiver
  ): Netty4StreamTransport[Response, Request] =
    new Server(id, writer, minAccumFrames, stats)

}
