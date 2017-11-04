package com.twitter.finagle.buoyant.h2
package netty4

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import com.twitter.conversions.time._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{ChannelClosedException, Failure}
import com.twitter.logging.Logger
import com.twitter.util._
import io.netty.handler.codec.http2.{Http2Frame, Http2GoAwayFrame, Http2StreamFrame}
import scala.annotation.tailrec
import scala.collection.JavaConverters._

trait Netty4DispatcherBase[SendMsg <: Message, RecvMsg <: Message] {

  protected[this] def log: Logger
  protected[this] def prefix: String
  protected[this] def stats: StatsReceiver
  protected[this] implicit def timer: Timer

  protected[this] def transport: Transport[Http2Frame, Http2Frame]
  protected[this] lazy val writer: H2Transport.Writer = Netty4H2Writer(transport)

  protected[this] val streamTTLSecs: Duration = 60.seconds // TODO: is this reasonable? should this be configurable?

  /**
   * The various states a stream can be in (particularly closed).
   *
   * The failures are distinguished so that the dispatcher can be
   * smart about (not) emiting resets to the remote.
   */
  private[this] sealed trait StreamTransport {
    def reset(err: Reset): Unit = {}
  }

  private[this] trait SelfReaping {
    def id: Int
    log.trace("[%s S:%d] stream %d will reap itself in %s", prefix, id, id, streamTTLSecs)
    Future.sleep(streamTTLSecs).ensure {
      val _ = streams.remove(id)
      log.debug("[%s S:%d] reaped", prefix, id)
    }
  }

  private[this] case class StreamOpen(stream: Netty4StreamTransport[SendMsg, RecvMsg]) extends StreamTransport {
    def toClosed: Option[StreamClosed] =
      this.stream.resetClient.map { resetP => StreamClosed(stream.streamId, resetP) }
    override def reset(err: Reset): Unit = stream.remoteReset(err)
  }

  private[this] case class StreamClosed(id: Int, onReset: Promise[Unit]) extends StreamTransport {

    onReset.setInterruptHandler {
      // the ClientFinished exception is raised to indicate that we no longer
      // need to track this closed stream for resetting the client stream.
      case Netty4StreamTransport.ClientFinished =>
        log.debug("[%s S:%d] client stream finished, discarding StreamClosed...", prefix, id)
        streams.remove(id); ()
    }

    override def reset(err: Reset): Unit = synchronized {
      log.debug("[%s S:%d] propagating %s to client half of closed stream", prefix, id, err)
      onReset.setException(err)
      streams.remove(id); ()
    }
  }

  private[this] case class StreamLocalReset(override val id: Int)
    extends StreamTransport with SelfReaping
  private[this] case class StreamRemoteReset(override val id: Int)
    extends StreamTransport with SelfReaping
  private[this] case class StreamFailed(cause: Throwable, override val id: Int)
    extends StreamTransport with SelfReaping

  private[this] val streams: ConcurrentHashMap[Int, StreamTransport] = new ConcurrentHashMap


  private[this] val closed: AtomicBoolean = new AtomicBoolean(false)
  protected[this] def isClosed = closed.get

  protected[this] val streamsGauge = stats.addGauge("open_streams") {
    streams.size()
  }

  private[this] val closedId: AtomicInteger = new AtomicInteger(0)
  @tailrec private[this] def addClosedId(id: Int): Unit = {
    val i = closedId.get
    val max = if (id > i) id else i
    if (!closedId.compareAndSet(i, max)) addClosedId(id)
  }

  protected[this] def demuxing: Future[Unit]

  protected[this] def registerStream(
    id: Int,
    stream: Netty4StreamTransport[SendMsg, RecvMsg]
  ): Unit = {
    val open = StreamOpen(stream)
    if (streams.putIfAbsent(id, open) != null) {
      throw new IllegalStateException(s"stream ${stream.streamId} already exists")
    }
    log.debug("[%s S:%d] initialized stream", prefix, id)
    val _ = stream.onReset.respond {
      case Return(_) =>
        // Free and clear.
        addClosedId(id)
        open.toClosed match {
          case Some(closed) if streams.replace(id, open, closed) =>
            // the open stream is a server dispatcher stream whose corresponding
            // client stream may not be closed yet. we need to continue tracking
            // it in the event of a reset.
            log.debug("[%s S:%d] open stream replaced by StreamClosed", prefix, id)
          case _ =>
            // either the stream is a client dispatcher stream, or the stream
            // transitioned to closed from a state other than open. just
            // remove it.
            log.debug("[%s S:%d] open stream removed", prefix, id)
            streams.remove(id)

        }
        log.debug("[%s S:%d] stream closed", prefix, id)

      case Throw(StreamError.Remote(e)) =>
        // The remote initiated a reset, so just update the state and
        // do nothing else.
        if (streams.replace(id, open, StreamRemoteReset(id))) {
          e match {
            case rst: Reset => log.debug("[%s S:%d] stream reset from remote: %s", prefix, id, rst)
            case e => log.error(e, "[%s S:%d] stream reset from remote", prefix, id)
          }
        }

      case Throw(StreamError.Local(e)) =>
        // The local side initiated a reset, so send a reset to
        // the remote.
        if (streams.replace(id, open, StreamLocalReset(id))) {
          log.debug("[%s S:%d] stream reset from local; resetting remote: %s", prefix, id, e)
          val rst = e match {
            case rst: Reset => rst
            case _ => Reset.Cancel
          }
          if (!closed.get) { writer.reset(id, rst); () }
        }

      case Throw(e) =>
        if (streams.replace(id, open, StreamFailed(e, id))) {
          log.error(e, "[%s S:%d] stream reset", prefix, id)
          if (!closed.get) { writer.reset(id, Reset.InternalError); () }
        }
    }
  }

  private[this] val wrapRemoteEx: PartialFunction[Throwable, Future[Http2Frame]] = {
    case e => Future.exception(StreamError.Remote(e))
  }

  private[this] def readFromTransport(): Future[Http2Frame] =
    transport.read().rescue(wrapRemoteEx)

  protected[this] def demux(): Future[Unit] = {
    lazy val loop: Try[Http2Frame] => Future[Unit] = {
      case Throw(e) if closed.get =>
        log.debug("[%s] dispatcher closed: %s", prefix, e)
        Future.exception(e)
      // if we attempted to cast a Http2Frame as a H1 frame, log a message & kill the
      // connection.
      case Throw(e: ClassCastException) if e.getMessage.contains("Transport.cast failed") =>
        log.warning("[%s] HTTP/2 router could not handle non-HTTP/2 request!", prefix)
        Future.Unit
      // if all streams have already been closed, then this just means that
      // the client failed to send a GOAWAY frame...
      case Throw(e: ChannelClosedException) if streams.isEmpty =>
        // ...so we don't need to propagate the exception
        log.debug("[%s] client closed connection without sending GOAWAY frame", prefix)
        Future.Unit

      case Throw(e) =>
        log.error(e, "[%s] dispatcher failed", prefix)
        goAway(GoAway.InternalError).before(Future.exception(e))

      case Return(_: Http2GoAwayFrame) =>
        if (resetStreams(Reset.Cancel)) transport.close()
        else Future.Unit

      case Return(f: Http2StreamFrame) =>
        f.streamId match {
          case 0 =>
            val e = new IllegalArgumentException(s"unexpected frame on stream 0: ${f.name}")
            goAway(GoAway.ProtocolError).before(Future.exception(e))

          case id =>
            streams.get(id) match {
              case null if id <= closedId.get =>
                // The stream has been closed and should know better than
                // to send us messages.
                writer.reset(id, Reset.Closed)

              case null =>
                demuxNewStream(f).before {
                  if (closed.get) Future.Unit
                  else transport.read().transform(loop)
                }

              case StreamOpen(st) =>
                st.recv(f)
                if (closed.get) Future.Unit
                else transport.read().transform(loop)

              case StreamLocalReset(_) | StreamFailed(_, _) =>
                // The local stream was already reset, but we may still
                // receive frames until the remote is notified.  Just
                // disregard these frames.
                if (closed.get) Future.Unit
                else transport.read().transform(loop)

              case StreamRemoteReset(_) =>
                // The stream has been reset and should know better than
                // to send us messages.
                writer.reset(id, Reset.Closed)
            }
        }

      case Return(f) =>
        log.error("[%s] unexpected frame: %s", prefix, f.name)
        val e = new IllegalArgumentException(s"unexpected frame on new stream: ${f.name}")
        goAway(GoAway.ProtocolError).before(Future.exception(e))
    }

    transport.read().transform(loop)
  }

  protected[this] def demuxNewStream(frame: Http2StreamFrame): Future[Unit]

  private[this] def resetStreams(err: Reset): Boolean =
    if (closed.compareAndSet(false, true)) {
      log.debug("[%s] resetting all streams: %s", prefix, err)
      streams.values.asScala.foreach { _.reset(err) }
      demuxing.raise(Failure(err).flagged(Failure.Interrupted))
      true
    } else false

  protected[this] def goAway(err: GoAway, deadline: Time = Time.Top): Future[Unit] = {
    log.debug("[%s] go away: %s", prefix, err)
    if (resetStreams(Reset.Cancel)) writer.goAway(err, deadline)
    else Future.Unit
  }

  protected[this] val onTransportClose: Throwable => Unit = { e =>
    log.debug("[%s] transport closed: %s", prefix, e)
    resetStreams(Reset.Cancel); ()
  }
}