package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.{ChannelClosedException, Failure, Service, Status => SvcStatus}
import com.twitter.finagle.stats.{StatsReceiver => FStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util._
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.JavaConverters._
import scala.util.control.NoStackTrace

object Netty4ClientDispatcher {
  private val log = Logger.get(getClass.getName)
  private val BaseStreamId = 3 // ID=1 is reserved for HTTP/1 upgrade
  private val MaxStreamId = (math.pow(2, 31) - 1).toInt

  private sealed trait StreamTransport
  private case class StreamOpen(stream: Netty4StreamTransport[Request, Response]) extends StreamTransport
  private object StreamClosed extends StreamTransport
  private object StreamLocalReset extends StreamTransport
  private object StreamRemoteReset extends StreamTransport
  private case class StreamFailed(cause: Throwable) extends StreamTransport
}

/**
 * Expose a single HTTP2 connection as a Service.
 *
 * The provided Transport[Http2Frame, Http2Frame] models a single
 * HTTP2 connection.xs
 */
class Netty4ClientDispatcher(
  transport: Transport[Http2Frame, Http2Frame],
  streamStats: Netty4StreamTransport.StatsReceiver
) extends Service[Request, Response] {

  import Netty4ClientDispatcher._

  private[this] val writer = Netty4H2Writer(transport)

  private[this] val prefix = s"C L:${transport.localAddress} R:${transport.remoteAddress}"

  private[this] val _id = new AtomicInteger(BaseStreamId)

  private[this] def nextId(): Int = _id.getAndAdd(2) match {
    case id if id < BaseStreamId || MaxStreamId < id =>
      // If the ID overflows, we can't use this connection anymore, so
      // we try to indicate to the server by sending a GO_AWAY in
      // accordance with the RFC.
      goAway(GoAway.ProtocolError)
      throw new IllegalArgumentException("stream id overflow")

    case id => id
  }

  private[this] val streams =
    new ConcurrentHashMap[Int, StreamTransport]

  private[this] val closed = new AtomicBoolean(false)

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStreamTransport(): Netty4StreamTransport[Request, Response] = {
    val id = nextId()
    val stream = Netty4StreamTransport.client(id, writer, streamStats)
    val open = StreamOpen(stream)
    if (streams.putIfAbsent(id, open) != null) {
      throw new IllegalStateException(s"stream ${stream.streamId} already exists")
    }
    log.debug("[%s S:%d] initialized stream", prefix, id)
    stream.onReset.respond {
      case Return(_) =>
        // Free and clear.
        if (streams.replace(id, open, StreamClosed)) {
          log.debug("[%s S:%d] stream closed", prefix, id)
        }

      case Throw(StreamError.Remote(e)) =>
        // The downstream remote initiated a reset, so just update the state and
        // do nothing else.
        if (streams.replace(id, open, StreamRemoteReset)) {
          log.debug(e, "[%s S:%d] stream reset from remote", prefix, id)
        }

      case Throw(StreamError.Local(e)) =>
        // The upstream local initiated a reset, so send a reset to
        // the downstream remote.
        if (streams.replace(id, open, StreamLocalReset)) {
          log.debug(e, "[%s S:%d] stream reset from local; resetting remote", prefix, id)
          val rst = e match {
            case rst: Reset => rst
            case _ => Reset.Cancel
          }
          if (!closed.get) { writer.reset(id, rst); () }
        }

      case Throw(e) =>
        if (streams.replace(id, open, StreamFailed(e))) {
          log.error(e, "[%s S:%d] stream reset", prefix, id)
          if (!closed.get) { writer.reset(id, Reset.InternalError); () }
        }
    }
    stream
  }

  /**
   * Continually read frames from the HTTP2 transport. Demultiplex
   * frames from the transport onto a per-stream receive queue.
   */
  private[this] val reading = {
    lazy val loop: Try[Http2Frame] => Future[Unit] = {
      case Throw(_: ChannelClosedException) => Future.Unit

      case Throw(e) =>
        log.error(e, "[%s] dispatcher failed", prefix)
        goAway(GoAway.InternalError)

      case Return(_: Http2GoAwayFrame) =>
        if (resetStreams(Reset.Cancel)) transport.close()
        else Future.Unit

      case Return(f: Http2StreamFrame) =>
        f.streamId match {
          case 0 => goAway(GoAway.ProtocolError)
          case id =>
            streams.get(id) match {
              case null => goAway(GoAway.ProtocolError)

              case StreamOpen(st) =>
                st.admitRemote(f)
                if (closed.get) Future.Unit
                else transport.read().transform(loop)

              case StreamLocalReset | StreamFailed(_) =>
                // The local stream was already reset, but we may still
                // receive frames until the remote is notified.  Just
                // disregard these frames.
                if (closed.get) Future.Unit
                else transport.read().transform(loop)

              case StreamClosed | StreamRemoteReset =>
                // The stream has been closed and should know better than
                // to send us messages.
                writer.reset(id, Reset.Closed)
            }
        }

      case Return(f) =>
        log.error("[%s] unexpected frame: %s", prefix, f.name)
        goAway(GoAway.ProtocolError)
    }

    transport.read().transform(loop)
  }

  /**
   * Write a request on the underlying connection and return its
   * response when it is received.
   */
  override def apply(req: Request): Future[Response] = {
    val st = newStreamTransport()
    // Stream the request while receiving the response and
    // continue streaming the request until it is complete,
    // canceled,  or the response fails.
    val initF = st.write(req)

    // Compose the response future and the stream write so that we
    // cancel the entire pipeline on reset.
    val writeF = initF.flatten

    // If the stream is reset prematurely, cancel the pending write
    st.onReset.onFailure {
      case StreamError.Remote(rst: Reset) => writeF.raise(rst)
      case StreamError.Remote(e) => writeF.raise(Reset.Cancel)
      case e => writeF.raise(e)
    }

    initF.unit.before(st.onRemoteMessage)
  }

  override def status: SvcStatus =
    if (closed.get) SvcStatus.Closed
    else SvcStatus.Open

  private[this] def resetStreams(err: Reset): Boolean =
    if (closed.compareAndSet(false, true)) {
      log.debug("[%s] resetting all streams: %s", prefix, err)
      streams.values.asScala.foreach {
        case StreamOpen(st) =>
          st.remoteReset(err); ()
        case _ =>
      }
      reading.raise(Failure(err).flagged(Failure.Interrupted))
      true
    } else false

  // If the connection is lost, reset active streams.
  transport.onClose.onSuccess { e =>
    log.debug(e, "[%s] transport closed", prefix)
    resetStreams(Reset.Cancel); ()
  }

  private[this] def goAway(err: GoAway, deadline: Time = Time.Top): Future[Unit] = {
    log.debug("[%s] go away: %s", prefix, err)
    if (resetStreams(Reset.Cancel)) writer.goAway(err, deadline)
    else Future.Unit
  }

  override def close(d: Time): Future[Unit] = goAway(GoAway.NoError, d)

}
