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

  import Netty4StreamTransport.{Pending, RemoteState, log}

  def streamId: Int
  protected[this] def transport: H2Transport.Writer
  protected[this] def minAccumFrames: Int
  protected[this] def statsReceiver: StatsReceiver
  protected[this] def mkRemote(headers: Http2Headers, stream: Stream): Remote

  private[this] val remoteQUsec = statsReceiver.stat("remoteq_usec")
  private[this] val remoteReadMsec = statsReceiver.stat("remote_read_msec")

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
      case RemoteState.Closed => false
      case RemoteState.Init(q) =>
        q.offer(Pending(frame, Stopwatch.start()))
      case RemoteState.Streaming(q, _) =>
        q.offer(Pending(frame, Stopwatch.start()))
    }

  /**
   * Immediately start reading from the remote queue.
   */
  private[this] val readingRemote: Future[Unit] = {
    val readRemoteFrame: Pending => Future[Unit] =
      frame => readRemoteFrame0(frame)

    val accumRemoteFrames: Queue[Pending] => Future[Unit] =
      frames => accumRemoteFrames0(frames)

    def loop(): Future[Unit] = remoteState.get match {
      case RemoteState.Init(q) =>
        q.poll().flatMap(readRemoteFrame).before(loop())

      case RemoteState.Streaming(q, _) if q.size >= minAccumFrames =>
        Future.const(q.drain()).flatMap(accumRemoteFrames).before(loop())

      case RemoteState.Streaming(q, _) =>
        q.poll().flatMap(readRemoteFrame).before(loop())

      case RemoteState.Closed => Future.Unit
    }

    loop().respond {
      case Return(_) =>
      case Throw(e) => log.error(e, "stream %d read error", streamId)
    }
  }

  @tailrec private[this] def readRemoteFrame0(p: Pending): Future[Unit] =
    remoteState.get match {
      case init@RemoteState.Init(q) =>
        p match {
          case Pending(f: Http2HeadersFrame, t0) if f.isEndStream =>
            remoteQUsec.add(t0().inMicroseconds)
            if (!remoteState.compareAndSet(init, RemoteState.Closed)) readRemoteFrame0(p)
            else {
              remoteReadMsec.add(t0().inMicroseconds)
              val msg = mkRemote(f.headers, Stream.Nil)
              remoteP.setValue(msg)
              if (isLocalClosed) closeP.setDone()
              Future.Unit
            }

          case Pending(f: Http2HeadersFrame, t0) =>
            remoteQUsec.add(t0().inMicroseconds)
            val stream = Stream()
            val streaming = RemoteState.Streaming(q, stream)
            if (!remoteState.compareAndSet(init, streaming)) readRemoteFrame0(p)
            else {
              remoteReadMsec.add(t0().inMicroseconds)
              val msg = mkRemote(f.headers, stream)
              remoteP.setValue(msg)
              Future.Unit
            }

          case Pending(frame, t0) =>
            remoteQUsec.add(t0().inMicroseconds)
            Future.exception(new IllegalArgumentException(s"unexpected frame: $frame"))
        }

      case streaming@RemoteState.Streaming(_, stream) =>
        def close() = remoteState.compareAndSet(streaming, RemoteState.Closed)
        p match {
          case Pending(df: Http2DataFrame, t0) =>
            remoteQUsec.add(t0().inMicroseconds)
            if (df.isEndStream && !close()) readRemoteFrame0(p)
            else {
              stream.write(Netty4Message.Data(df, updateWindow))
                .onSuccess(_ => remoteReadMsec.add(t0().inMicroseconds))
            }

          case Pending(tf: Http2HeadersFrame, t0) if tf.isEndStream =>
            remoteQUsec.add(t0().inMicroseconds)
            if (!close()) readRemoteFrame0(p)
            else {
              stream.write(Netty4Message.Trailers(tf.headers))
                .onSuccess(_ => remoteReadMsec.add(t0().inMicroseconds))
            }

          case Pending(frame, t0) =>
            remoteQUsec.add(t0().inMicroseconds)
            Future.exception(new IllegalArgumentException(s"unexpected frame: ${frame}"))
        }

      case RemoteState.Closed =>
        Future.exception(new IllegalStateException(s"read frame while closed: ${p.frame}"))
    }

  @tailrec private[this] def accumRemoteFrames0(frames: Queue[Pending]): Future[Unit] = {
    require(frames.nonEmpty)
    remoteState.get match {
      case streaming@RemoteState.Streaming(q, stream) =>
        val start = Stopwatch.start()
        var content: CompositeByteBuf = null
        var bytes = 0
        var dataEos = false
        var trailers: Frame.Trailers = null

        var dataReleased: () => Unit = () => ()
        def onDataRelease(t0: Stopwatch.Elapsed): Unit = {
          val dr = dataReleased
          dataReleased = () => {
            dr()
            remoteReadMsec.add(t0().inMillis)
          }
        }
        var trailersReleased: () => Unit = () => ()

        val nFrames = frames.length
        val iter = frames.iterator
        while (!dataEos && trailers == null && iter.hasNext) {
          iter.next() match {
            case Pending(f: Http2DataFrame, t0) =>
              remoteQUsec.add(t0().inMicroseconds)
              bytes += f.content.readableBytes + f.padding
              // Initialize content using the first frame's allocator
              if (content == null) {
                content = f.content.alloc.compositeBuffer(nFrames)
              }
              content.addComponent(true /*advance widx*/ , f.content)
              dataEos = f.isEndStream
              onDataRelease(t0)

            case Pending(f: Http2HeadersFrame, t0) =>
              remoteQUsec.add(t0().inMicroseconds)
              trailers = Netty4Message.Trailers(f.headers)
              val tr = trailersReleased
              trailersReleased = () => remoteReadMsec.add(t0().inMillis)

            case Pending(_, t0) =>
              remoteQUsec.add(t0().inMicroseconds)
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
              stream.write(Frame.Data(buf, dataEos, release)).onSuccess(_ => dataReleased())
            }

          dataWritten.before {
            trailers match {
              case null => Future.Unit
              case trailers => stream.write(trailers).onSuccess(_ => trailersReleased())
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

  private[this] val writeFrame: Frame => Future[Boolean] = { frame =>
    val writeF = frame match {
      case data: Frame.Data =>
        transport.write(streamId, data)
          .before(data.release())
          .map(_ => data.isEnd)

      case tlrs: Frame.Trailers =>
        transport.write(streamId, tlrs)
          .before(tlrs.release())
          .before(Future.True)
    }
    if (frame.isEnd) writeF.ensure(setLocalClosed())
    writeF
  }

  protected[this] def prefix: String

  private[this] val updateWindow: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}

object Netty4StreamTransport {
  private val log = Logger.get(getClass.getName)

  private case class Pending(frame: Http2StreamFrame, insertedAt: Stopwatch.Elapsed)
  private sealed trait RemoteState
  private object RemoteState {
    case class Init(q: AsyncQueue[Pending]) extends RemoteState
    case class Streaming(q: AsyncQueue[Pending], stream: Stream.Writer) extends RemoteState
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
