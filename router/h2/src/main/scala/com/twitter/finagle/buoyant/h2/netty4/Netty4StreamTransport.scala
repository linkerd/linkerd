package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Failure
import com.twitter.finagle.netty4.ByteBufAsBuf
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Queue

private[h2] trait Netty4StreamTransport[Local <: Message, Remote <: Message] {

  import Netty4StreamTransport.{RemoteState, log}

  def streamId: Int
  protected[this] def transport: H2Transport.Writer
  protected[this] def minAccumFrames: Int
  protected[this] def statsReceiver: StatsReceiver
  protected[this] def mkRemote(headers: Http2Headers, stream: Stream): Remote

  @volatile private[this] var isLocalClosed = false
  def isClosed = isLocalClosed && remoteState.get == RemoteState.Closed

  private[this] val closeP = new Promise[Unit]
  def onClose: Future[Unit] = closeP

  private[this] def setLocalClosed(): Unit = {
    isLocalClosed = true
    if (isClosed) { closeP.setDone(); () }
  }

  def close(): Future[Unit] =
    if (isClosed) Future.Unit
    else {
      setLocalClosed()
      remoteState.set(RemoteState.Closed)
      closeP.setDone()
      transport.resetNoError(streamId)
    }

  private[this] val remoteP = new Promise[Remote]
  def remote: Future[Remote] = remoteP

  /**
   * Supports writing frames _FROM_ the remote transport to the `remote` stream.
   */
  private[this] val remoteState =
    new AtomicReference[RemoteState](RemoteState.Init(new AsyncQueue))

  /**
   * A Dispatcher reads frames from the remote and offers them into the stream.
   *
   * If an initial Headers frame is offered, the `remote` future is satisfied.
   */
  def offerRemote(frame: Http2StreamFrame): Boolean =
    remoteState.get match {
      case RemoteState.Closed =>
        log.debug("stream %d discarding: %s", streamId, frame.name)
        false
      case RemoteState.Init(q) =>
        log.debug("stream %d init offer: %s", streamId, frame.name)
        q.offer(frame)
      case RemoteState.Streaming(q, _) =>
        log.debug("stream %d streaming offer: %s", streamId, frame.name)
        q.offer(frame)
    }

  private[this] val readRemoteFrame: Http2StreamFrame => Future[Unit] =
    frame => readRemoteFrame0(frame)

  private[this] val accumRemoteFrames: Queue[Http2StreamFrame] => Future[Unit] =
    frames => accumRemoteFrames0(frames)

  /**
   * Immediately start reading from the remote queue.
   */
  private[this] val readingRemote: Future[Unit] = {
    def loop(): Future[Unit] = remoteState.get match {
      case RemoteState.Init(q) =>
        log.debug("stream %d init poll", streamId)
        q.poll().flatMap(readRemoteFrame).before(loop())

      case RemoteState.Streaming(q, _) if q.size >= minAccumFrames =>
        log.debug("stream %d streaming drain", streamId)
        Future.const(q.drain()).flatMap(accumRemoteFrames).before(loop())

      case RemoteState.Streaming(q, _) =>
        log.debug("stream %d streaming poll", streamId)
        q.poll().flatMap(readRemoteFrame).before(loop())

      case RemoteState.Closed => Future.Unit
    }

    loop().respond {
      case Return(_) => log.debug("stream %d finished reading", streamId)
      case Throw(e) => log.error(e, "stream %d read error", streamId)
    }
  }

  @tailrec private[this] def readRemoteFrame0(frame: Http2StreamFrame): Future[Unit] =
    remoteState.get match {
      case init@RemoteState.Init(q) =>
        log.debug("stream %d init read: %s", streamId, frame.name)
        frame match {
          case f: Http2HeadersFrame if f.isEndStream =>
            if (!remoteState.compareAndSet(init, RemoteState.Closed)) readRemoteFrame0(f)
            else {
              if (isLocalClosed) closeP.setDone()
              val msg = mkRemote(f.headers, Stream.Nil)
              remoteP.setValue(msg)
              Future.Unit
            }

          case f: Http2HeadersFrame =>
            val stream = Stream()
            val streaming = RemoteState.Streaming(q, stream)
            if (!remoteState.compareAndSet(init, streaming)) readRemoteFrame0(f)
            else {
              val msg = mkRemote(f.headers, stream)
              remoteP.setValue(msg)
              Future.Unit
            }

          case _ => Future.exception(new IllegalArgumentException(s"unexpected frame: $frame"))
        }

      case streaming@RemoteState.Streaming(_, stream) =>
        log.debug("stream %d streaming read: %s", streamId, frame.name)
        def close() = remoteState.compareAndSet(streaming, RemoteState.Closed)
        frame match {
          case df: Http2DataFrame =>
            if (df.isEndStream && !close()) readRemoteFrame0(frame)
            else stream.write(Netty4Message.Data(df, updateWindow))

          case tf: Http2HeadersFrame if tf.isEndStream =>
            if (!close()) readRemoteFrame0(frame)
            else stream.write(Netty4Message.Trailers(tf.headers))

          case frame => Future.exception(new IllegalArgumentException(s"unexpected frame: $frame"))
        }

      case RemoteState.Closed =>
        log.debug("stream %d closed read: %s", streamId, frame.name)
        Future.exception(new IllegalStateException(s"read frame while closed: $frame"))
    }

  @tailrec private[this] def accumRemoteFrames0(frames: Queue[Http2StreamFrame]): Future[Unit] = {
    require(frames.nonEmpty)
    remoteState.get match {
      case streaming@RemoteState.Streaming(q, stream) =>
        val start = Stopwatch.start()
        var content: CompositeByteBuf = null
        var bytes = 0
        var dataEos = false
        var trailers: Frame.Trailers = null

        val nFrames = frames.length
        val iter = frames.iterator
        while (!dataEos && trailers == null && iter.hasNext) {
          iter.next() match {
            case f: Http2DataFrame =>
              bytes += f.content.readableBytes + f.padding
              // Initialize content using the first frame's allocator
              if (content == null) {
                content = f.content.alloc.compositeBuffer(nFrames)
              }
              content.addComponent(true /*advance widx*/ , f.content)
              dataEos = f.isEndStream

            case f: Http2HeadersFrame =>
              trailers = Netty4Message.Trailers(f.headers)

            case _ =>
          }
        }

        if ((dataEos || trailers != null) && !remoteState.compareAndSet(streaming, RemoteState.Closed)) {
          accumRemoteFrames0(frames)
        } else {
          val dataWritten =
            if (content == null) Future.Unit
            else {
              val buf = ByteBufAsBuf.Owned(content.retain())
              val release: () => Future[Unit] =
                if (bytes > 0) () => updateWindow(bytes)
                else () => Future.Unit
              stream.write(Frame.Data(buf, dataEos, release))
            }

          dataWritten.before {
            trailers match {
              case null => Future.Unit
              case trailers => stream.write(trailers)
            }
          }
        }

      case state =>
        Future.exception(new IllegalStateException(s"invalid remote stream state: $state"))
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
          .before(tlrs.release())
          .before(Future.True)
    }
    if (v.isEnd) writeF.ensure(setLocalClosed())
    writeF
  }

  protected[this] def prefix: String

  private[this] val updateWindow: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}

object Netty4StreamTransport {
  private val log = Logger.get(getClass.getName)

  private sealed trait RemoteState
  private object RemoteState {
    case class Init(q: AsyncQueue[Http2StreamFrame]) extends RemoteState
    case class Streaming(q: AsyncQueue[Http2StreamFrame], stream: Stream.Writer) extends RemoteState
    object Closed extends RemoteState
  }

  private[this] class Client(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val minAccumFrames: Int,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Request, Response] {

    override protected[this] def prefix: String = s"client: stream $streamId"

    override protected[this] def mkRemote(headers: Http2Headers, stream: Stream): Response =
      Response(Netty4Message.Headers(headers), stream)
  }

  private[this] class Server(
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
    transport: H2Transport.Writer,
    minAccumFrames: Int,
    stats: StatsReceiver = NullStatsReceiver
  ): Netty4StreamTransport[Request, Response] =
    new Client(id, transport, minAccumFrames, stats)

  def server(
    id: Int,
    transport: H2Transport.Writer,
    minAccumFrames: Int,
    stats: StatsReceiver = NullStatsReceiver
  ): Netty4StreamTransport[Response, Request] =
    new Server(id, transport, minAccumFrames, stats)

}
