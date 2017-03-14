package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.{ChannelClosedException, Failure}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util._
import io.netty.handler.codec.http2.{Http2Frame, Http2GoAwayFrame, Http2StreamFrame}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._

trait Netty4DispatcherBase[SendMsg <: Message, RecvMsg <: Message] {

  protected[this] def log: Logger
  protected[this] def prefix: String

  protected[this] def transport: Transport[Http2Frame, Http2Frame]
  protected[this] lazy val writer: H2Transport.Writer = Netty4H2Writer(transport)

  /**
   * The various states a stream can be in (particularly closed).
   *
   * The failures are distinguished so that the dispatcher can be
   * smart about (not) emiting resets to the remote.
   *
   * TODO only track closed streams for a TTL after it has closed.
   */
  private[this] sealed trait StreamTransport
  private[this] case class StreamOpen(stream: Netty4StreamTransport[SendMsg, RecvMsg]) extends StreamTransport
  private[this] object StreamClosed extends StreamTransport
  private[this] object StreamLocalReset extends StreamTransport
  private[this] object StreamRemoteReset extends StreamTransport
  private[this] case class StreamFailed(cause: Throwable) extends StreamTransport

  private[this] val streams: ConcurrentHashMap[Int, StreamTransport] = new ConcurrentHashMap
  private[this] val closed: AtomicBoolean = new AtomicBoolean(false)
  protected[this] def isClosed = closed.get

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
        if (streams.replace(id, open, StreamClosed)) {
          log.debug("[%s S:%d] stream closed", prefix, id)
        }

      case Throw(StreamError.Remote(e)) =>
        // The remote initiated a reset, so just update the state and
        // do nothing else.
        if (streams.replace(id, open, StreamRemoteReset)) {
          e match {
            case rst: Reset => log.debug("[%s S:%d] stream reset from remote: %s", prefix, id, rst)
            case e => log.error(e, "[%s S:%d] stream reset from remote", prefix, id)
          }
        }

      case Throw(StreamError.Local(e)) =>
        // The local side initiated a reset, so send a reset to
        // the remote.
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
  }

  private[this] val wrapRemoteEx: PartialFunction[Throwable, Future[Http2Frame]] = {
    case e => Future.exception(StreamError.Remote(e))
  }

  private[this] def readFromTransport(): Future[Http2Frame] =
    transport.read().rescue(wrapRemoteEx)

  protected[this] def demux(): Future[Unit] = {
    lazy val loop: Try[Http2Frame] => Future[Unit] = {
      case Throw(e) if closed.get =>
        log.debug(e, "[%s] dispatcher closed", prefix)
        Future.exception(e)

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
              case null =>
                demuxNewStream(f).before {
                  if (closed.get) Future.Unit
                  else transport.read().transform(loop)
                }

              case StreamOpen(st) =>
                st.recv(f)
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
        val e = new IllegalArgumentException(s"unexpected frame on new stream: ${f.name}")
        goAway(GoAway.ProtocolError).before(Future.exception(e))
    }

    transport.read().transform(loop)
  }

  protected[this] def demuxNewStream(frame: Http2StreamFrame): Future[Unit]

  private[this] def resetStreams(err: Reset): Boolean =
    if (closed.compareAndSet(false, true)) {
      log.debug("[%s] resetting all streams: %s", prefix, err)
      streams.values.asScala.foreach {
        case StreamOpen(st) => st.remoteReset(err)
        case _ =>
      }
      demuxing.raise(Failure(err).flagged(Failure.Interrupted))
      true
    } else false

  protected[this] def goAway(err: GoAway, deadline: Time = Time.Top): Future[Unit] = {
    log.debug("[%s] go away: %s", prefix, err)
    if (resetStreams(Reset.Cancel)) writer.goAway(err, deadline)
    else Future.Unit
  }

  protected[this] val onTransportClose: Throwable => Unit = { e =>
    log.debug(e, "[%s] transport closed", prefix)
    resetStreams(Reset.Cancel); ()
  }
}
