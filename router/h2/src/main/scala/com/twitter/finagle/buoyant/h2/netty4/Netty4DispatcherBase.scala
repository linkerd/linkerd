package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Failure
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util._
import io.netty.handler.codec.http2.Http2Frame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._

trait Netty4DispatcherBase[LocalMsg <: Message, RemoteMsg <: Message] {

  protected[this] def log: Logger
  protected[this] def prefix: String

  protected[this] def transport: Transport[Http2Frame, Http2Frame]
  protected[this] lazy val writer: H2Transport.Writer = Netty4H2Writer(transport)

  protected[this] sealed trait StreamTransport
  protected[this] case class StreamOpen(stream: Netty4StreamTransport[LocalMsg, RemoteMsg]) extends StreamTransport
  protected[this] object StreamClosed extends StreamTransport
  protected[this] object StreamLocalReset extends StreamTransport
  protected[this] object StreamRemoteReset extends StreamTransport
  protected[this] case class StreamFailed(cause: Throwable) extends StreamTransport

  protected[this] val streams: ConcurrentHashMap[Int, StreamTransport] =
    new ConcurrentHashMap

  protected[this] val closed: AtomicBoolean = new AtomicBoolean(false)
  protected[this] def reading: Future[Unit]

  protected[this] def registerStream(
    id: Int,
    stream: Netty4StreamTransport[LocalMsg, RemoteMsg]
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
          log.debug(e, "[%s S:%d] stream reset from remote", prefix, id)
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

  protected[this] def resetStreams(err: Reset): Boolean =
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

  protected[this] def goAway(err: GoAway, deadline: Time = Time.Top): Future[Unit] =
    if (resetStreams(Reset.Cancel)) {
      log.debug("[%s] go away: %s", prefix, err)
      writer.goAway(err, deadline)
    } else Future.Unit

  protected[this] val onTransportClose: Throwable => Unit = { e =>
    log.debug(e, "[%s] transport closed", prefix)
    resetStreams(Reset.Cancel); ()
  }
}
