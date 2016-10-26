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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.collection.immutable.Queue

/**
 * Models a single HTTP/2 stream.
 *
 * Transports send a `Local`-typed message via an underlying
 * [[H2Transport.Writer]]. A dispatcher, which models a single HTTP/2
 * connection, provides the transport with `Http2StreamFrame`
 * instances that are used to build a `Remote`-typed message.
 */
private[h2] trait Netty4StreamTransport[LocalMsg <: Message, RemoteMsg <: Message] {

  import Netty4StreamTransport._

  /** The HTTP/2 STREAM_ID of this stream. */
  def streamId: Int

  protected[this] def transport: H2Transport.Writer
  protected[this] def minAccumFrames: Int
  protected[this] def statsReceiver: StatsReceiver
  protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): RemoteMsg

  /*
   * Remote frames are offered to the StreamTransport from a
   * Dispatcher. They are immediately enqueued in `remoteQ` and are
   * dequeued as the remote message/stream is read from the transport.
   */
  private[this] val remoteQUsec = statsReceiver.stat("remoteq_usec")
  private[this] case class QFrame(frame: Http2StreamFrame, elapsed: Stopwatch.Elapsed)
  private[this] val recordQ: QFrame => Http2StreamFrame = { qf =>
    remoteQUsec.add(qf.elapsed().inMicroseconds)
    qf.frame
  }
  private[this] val recordDrainQ: Queue[QFrame] => Queue[Http2StreamFrame] =
    qfs => qfs.map(recordQ)

  // We don't set a queue limit because we have to admit all messages
  // the dispatcher gives us.  We rely on HTTP/2 flow control to keep
  // this reasonable.
  private[this] val remoteQ = new AsyncQueue[QFrame]

  private[this] val streamState = new AtomicReference[StreamState](StreamOpen)
  def isClosed = streamState.get == StreamClosed

  private[this] val closeP = new Promise[Unit]
  def onClose: Future[Unit] = closeP

  /** Close a stream, sending a RST_STREAM if appropriate. */
  @tailrec final def close(): Future[Unit] = streamState.get match {
    case StreamClosed => Future.Unit
    case s0 =>
      streamState.compareAndSet(s0, StreamClosed) match {
        case false => close()
        case true =>
          remoteQ.fail(Failure("closed"), discard = false)
          val resetF = transport.resetNoError(streamId)
          closeP.become(resetF)
          resetF
      }
  }

  /** Mark the local stream as closed, firing closeP if appropriate. */
  @tailrec private[this] def setStreamLocalClosed(): Boolean = streamState.get match {
    case StreamHalfClosedLocal | StreamClosed => false
    case StreamOpen =>
      streamState.compareAndSet(StreamOpen, StreamHalfClosedLocal) match {
        case false => setStreamLocalClosed()
        case true => true
      }
    case StreamHalfClosedRemote =>
      streamState.compareAndSet(StreamHalfClosedRemote, StreamClosed) match {
        case false => setStreamLocalClosed()
        case true =>
          closeP.setDone()
          true
      }
  }

  /** Mark the remote stream as closed, firing closeP if appropriate. */
  @tailrec private[this] def setStreamRemoteClosed(): Boolean = streamState.get match {
    case StreamHalfClosedRemote | StreamClosed => false
    case StreamOpen =>
      streamState.compareAndSet(StreamOpen, StreamHalfClosedRemote) match {
        case false => setStreamRemoteClosed()
        case true =>
          remoteQ.fail(closedException, discard = true)
          true
      }
    case StreamHalfClosedLocal =>
      streamState.compareAndSet(StreamHalfClosedLocal, StreamClosed) match {
        case false => setStreamRemoteClosed()
        case true =>
          remoteQ.fail(closedException, discard = true)
          closeP.setDone()
          true
      }
  }

  /**
   * A Dispatcher reads frames from the remote and offers them into the stream.
   *
   * If an initial Headers frame is offered, the `remote` future is satisfied.
   */
  def offerRemoteFrame(frame: Http2StreamFrame): Boolean = streamState.get match {
    case StreamHalfClosedRemote | StreamClosed => false
    case _ => remoteQ.offer(QFrame(frame, Stopwatch.start()))
  }

  /**
   * Satisfied with a [[RemoteMsg]] once was is read on the stream.
   */
  lazy val remoteMsg: Future[RemoteMsg] = remoteQ.poll().map(recordQ).map {
    case f: Http2HeadersFrame =>
      // `streamState` is updated when an END_STREAM is returned is
      // processed in the messsage's stream.
      if (f.isEndStream) setStreamRemoteClosed()
      val stream = if (f.isEndStream) Stream.Nil else remoteStream
      mkRemoteMsg(f.headers, stream)

    case frame =>
      throw new IllegalArgumentException(s"unexpected frame: $frame")
  }

  /**
   */
  private[this] val remoteStream = new Stream.Reader {

    private[this] val endP = new Promise[Unit]
    def onEnd: Future[Unit] = endP

    /*
     */
    private[this] val remoteState: AtomicReference[RemoteState] =
      new AtomicReference(RemoteOpen)

    private[this] def setRemoteTrailing(h: Http2Headers): Unit =
      if (!remoteState.compareAndSet(RemoteOpen, RemoteTrailing(h)))
        throw new IllegalStateException("could not mark remote as draining")

    private[this] def setRemoteClosed(): Unit = {
      if (!remoteState.compareAndSet(RemoteOpen, RemoteClosed))
        throw new IllegalStateException("could not close remote")
      setStreamRemoteClosed()
      endP.setDone(); ()
    }

    @tailrec final def read(): Future[Frame] = remoteState.get match {
      case RemoteOpen =>
        if (remoteQ.size < minAccumFrames) remoteQ.poll().map(recordQ).map(toFrameUpdate)
        else Future.const(remoteQ.drain()).map(recordDrainQ).map(accumFramesUpdate)

      case s0@RemoteTrailing(headers) =>
        remoteState.compareAndSet(s0, RemoteClosed) match {
          case true => Future.value(Netty4Message.Trailers(headers))
          case false => read()
        }

      case RemoteClosed => Future.exception(closedException)
    }

    private[this] val toFrameUpdate: Http2StreamFrame => Frame = {
      case f: Http2DataFrame =>
        if (f.isEndStream) setRemoteClosed()
        Netty4Message.Data(f, updateWindow)

      case f: Http2HeadersFrame if f.isEndStream =>
        if (f.isEndStream) setRemoteClosed()
        Netty4Message.Trailers(f.headers)

      case f =>
        throw new IllegalArgumentException(s"unexpected frame: $f")
    }

    private[this] val accumFramesUpdate: Queue[Http2StreamFrame] => Frame = { frames =>
      require(frames.nonEmpty)
      val start = Stopwatch.start()
      var content: CompositeByteBuf = null
      var bytes = 0
      var dataEos = false
      var trailers: Http2Headers = null

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
            trailers = f.headers

          case f =>
            throw new IllegalArgumentException(s"unexpected stream frame: $f")
        }
      }

      content match {
        case null =>
          trailers match {
            case null => throw new IllegalArgumentException("no frames to accumulate")
            case trailers =>
              setRemoteClosed()
              Netty4Message.Trailers(trailers)
          }

        case content =>
          if (trailers != null) setRemoteTrailing(trailers)
          val buf = ByteBufAsBuf.Owned(content.retain())
          val release: () => Future[Unit] =
            if (bytes > 0) () => updateWindow(bytes)
            else () => Future.Unit
          Frame.Data(buf, dataEos, release)
      }

    }
  }

  private[this] val mapFutureUnit = (_: Any) => Future.Unit

  def write(msg: LocalMsg): Future[Future[Unit]] = msg.data match {
    case Stream.Nil =>
      writeHeaders(msg.headers, false).map(mapFutureUnit)
    case data: Stream.Reader =>
      writeHeaders(msg.headers, false).map { _ => writeStream(data) }
  }

  def writeHeaders(hdrs: Headers, eos: Boolean = false): Future[Unit] = {
    val tx = transport.write(streamId, hdrs, eos)
    if (eos) tx.ensure { setStreamLocalClosed(); () }
    tx
  }

  /** Write a request stream to the underlying transport */
  def writeStream(reader: Stream.Reader): Future[Unit] =
    if (reader.isEmpty) Future.Unit
    else {
      lazy val loop: Boolean => Future[Unit] = { eos =>
        if (eos) Future.Unit
        else reader.read().flatMap(writeFrame).flatMap(loop)
      }
      reader.read().flatMap(writeFrame).flatMap(loop)
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
    if (frame.isEnd) writeF.ensure { setStreamLocalClosed(); () }
    writeF
  }

  private[this] val updateWindow: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}

object Netty4StreamTransport {
  private val log = Logger.get(getClass.getName)

  private val closedException = Failure("closed")

  /**
   * The transport stream must be in a valid HTTP/2 State (RFC7540§5.1).
   *
   * The `Idle` state does not apply to transports, since Transports
   * are only created as a stream is opened.
   *
   * The `Reserved` state does not apply, since PUSH_PROMISE is not
   * supported.
   */
  private sealed trait StreamState
  private object StreamOpen extends StreamState
  private object StreamHalfClosedLocal extends StreamState
  private object StreamHalfClosedRemote extends StreamState
  private object StreamClosed extends StreamState

  private sealed trait RemoteState
  private object RemoteOpen extends RemoteState
  private case class RemoteTrailing(trailers: Http2Headers) extends RemoteState
  private object RemoteClosed extends RemoteState

  /*
   * Concrete transport implementations & constructors:
   */

  private[this] class Client(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val minAccumFrames: Int,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Request, Response] {

    override protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): Response =
      Response(Netty4Message.Headers(headers), stream)
  }

  private[this] class Server(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val minAccumFrames: Int,
    override protected[this] val statsReceiver: StatsReceiver = NullStatsReceiver
  ) extends Netty4StreamTransport[Response, Request] {

    override protected[this] def mkRemoteMsg(headers: Http2Headers, stream: Stream): Request =
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
