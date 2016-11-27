package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.{ChannelClosedException, ChannelWriteException, Failure, Service}
import com.twitter.finagle.context.{Contexts, RemoteInfo}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util._
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._
import scala.util.control.NoStackTrace

object Netty4ServerDispatcher {
  private val log = Logger.get(getClass.getName)

  private sealed trait StreamTransport
  private case class StreamOpen(stream: Netty4StreamTransport[Response, Request]) extends StreamTransport
  private object StreamClosed extends StreamTransport
  private object StreamLocalReset extends StreamTransport
  private object StreamRemoteReset extends StreamTransport
  private case class StreamFailed(cause: Throwable) extends StreamTransport
}

class Netty4ServerDispatcher(
  transport: Transport[Http2Frame, Http2Frame],
  service: Service[Request, Response],
  streamStats: Netty4StreamTransport.StatsReceiver
) extends Closable {

  import Netty4ServerDispatcher._

  private[this] val writer = Netty4H2Writer(transport)

  private[this] val closed = new AtomicBoolean(false)

  private[this] val streams = new ConcurrentHashMap[Int, StreamTransport]

  private[this] val prefix = s"S L:${transport.localAddress} R:${transport.remoteAddress}"

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStreamTransport(id: Int): Netty4StreamTransport[Response, Request] = {
    val stream = Netty4StreamTransport.server(id, writer, streamStats)
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
        // The upstream remote initiated a reset, so just update the state and
        // do nothing else.
        if (streams.replace(id, open, StreamRemoteReset)) {
          log.debug(e, "[%s S:%d] stream reset from remote", prefix, id)
        }

      case Throw(StreamError.Local(e)) =>
        // The downstream local initiated a reset, so send a reset to
        // the upstream remote.
        if (streams.replace(id, open, StreamLocalReset)) {
          log.debug(e, "[%s S:%d] stream reset from local; resetting remote", prefix, id)
          val rst = e match {
            case rst: Reset => rst
            case _ => Reset.Cancel
          }
          if (!closed.get) { writer.reset(id, rst); () }
        }

      case Throw(e) =>
        log.error(e, "[%s S:%d] unexpected reset", prefix, id)
        if (streams.replace(id, open, StreamFailed(e))) {
          log.debug(e, "[%s S:%d] stream reset", prefix, id)
          if (!closed.get) { writer.reset(id, Reset.InternalError); () }
        }
    }
    stream
  }

  private[this] case class ServiceException(cause: Throwable) extends NoStackTrace
  private[this] val wrapServiceEx: PartialFunction[Throwable, Future[Response]] = {
    case e => Future.exception(ServiceException(e))
  }

  private[this] val serve: Request => Future[Response] = { req =>
    val save = Local.save()
    try {
      Contexts.local.let(RemoteInfo.Upstream.AddressCtx, transport.remoteAddress) {
        transport.peerCertificate match {
          case None =>
            service(req).rescue(wrapServiceEx)

          case Some(cert) =>
            Contexts.local.let(Transport.peerCertCtx, cert) {
              service(req).rescue(wrapServiceEx)
            }
        }
      }
    } finally Local.restore(save)
  }

  private[this] def serveStream(st: Netty4StreamTransport[Response, Request]) = {
    // Note: `remoteMsg` should be satisfied immediately, since the
    // headers frame will have just been admitted to the stream.
    val serveF = st.onRemoteMessage.flatMap(serve).flatMap(st.write(_).flatten)

    // When the stream is reset, ensure that the cancelation is
    // propagated downstream.
    st.onReset.onFailure {
      case StreamError.Remote(rst: Reset) => serveF.raise(rst)
      case StreamError.Remote(e) => serveF.raise(Reset.Cancel)
      case e => serveF.raise(e)
    }

    val _ = serveF.onFailure {
      // The stream has already been reset.  Do nothing
      case _: StreamError =>

      // The service failed independently of streaming, reset the stream.
      case ServiceException(e) =>
        val rst = e match {
          case rst: Reset => rst
          case f@Failure(_) if f.isFlagged(Failure.Interrupted) => Reset.Cancel
          case f@Failure(_) if f.isFlagged(Failure.Rejected) => Reset.Refused
          case _ => Reset.InternalError
        }
        log.info(e, "[%s S:%d] service error; resetting remote %s", prefix, st.streamId, rst)
        st.localReset(rst)

      case e =>
        log.debug(e, "[%s S:%d] ignoring exception", prefix, st.streamId)
    }
  }

  /**
   * Continually read from the transport, creating new streams
   */
  private[this] val reading: Future[Unit] = {
    lazy val loop: Try[Http2Frame] => Future[Unit] = {
      case Throw(e: ChannelClosedException) =>
        log.debug(e, "[%s] read failed", prefix)
        resetStreams(Reset.Cancel)
        Future.Unit

      case Throw(e) =>
        log.error(e, "[%s] read failed", prefix)
        goAway(GoAway.InternalError)

      case Return(_: Http2GoAwayFrame) =>
        if (resetStreams(Reset.Cancel)) transport.close()
        else Future.Unit

      case Return(frame: Http2StreamFrame) if frame.streamId > 0 =>
        streams.get(frame.streamId) match {
          case null =>
            // The stream didn't exist. We're either receiving HEADERS
            // to initiate a new request, or we're receiving an
            // unexpected frame.  Unexpected frames cause the
            // connection to be closed with a protocol error.
            frame match {
              case frame: Http2HeadersFrame =>
                val stream = newStreamTransport(frame.streamId)
                if (stream.admitRemote(frame)) serveStream(stream)
                if (closed.get) Future.Unit
                else transport.read().transform(loop)

              case frame =>
                log.error("[%s S:%d] unexpected %s; sending GO_AWAY", prefix, frame.streamId, frame.name)
                goAway(GoAway.ProtocolError)
            }

          case StreamOpen(st) =>
            // The stream exists and is open, so feed it frames.
            st.admitRemote(frame)
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
            writer.reset(frame.streamId, Reset.Closed)
        }

      case Return(frame) =>
        log.warning("[%s] unexpected frame: %s", prefix, frame.name)
        goAway(GoAway.ProtocolError)
    }

    transport.read().transform(loop)
  }

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
    if (resetStreams(Reset.Cancel)) log.info(e, "[%s] transport closed", prefix)
    else log.debug(e, "[%s] transport closed", prefix)
  }

  private[this] def goAway(err: GoAway, deadline: Time = Time.Top): Future[Unit] =
    if (resetStreams(Reset.Cancel)) {
      log.debug("[%s] go away: %s", prefix, err)
      writer.goAway(err, deadline)
    } else Future.Unit

  override def close(deadline: Time): Future[Unit] =
    goAway(GoAway.NoError, deadline)

}
