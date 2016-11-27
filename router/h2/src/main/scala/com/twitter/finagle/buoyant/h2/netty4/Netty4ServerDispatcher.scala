package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.{ChannelClosedException, ChannelWriteException, Failure, Service}
import com.twitter.finagle.context.{Contexts, RemoteInfo}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util._
import io.netty.handler.codec.http2._
import scala.util.control.NoStackTrace

object Netty4ServerDispatcher {
  private val log = Logger.get(getClass.getName)

  /**
   * Indicates a failure on the downstream service (i.e. before a
   * response is being written).
   */
  private case class ServiceException(cause: Throwable) extends NoStackTrace
  private val wrapServiceEx: PartialFunction[Throwable, Future[Response]] = {
    case e => Future.exception(ServiceException(e))
  }
}

/**
 * A dispatcher that accepts requests from the upstream remote, serves
 * them through a downstream service, and writes the response back to
 * the upstream remote.
 */
class Netty4ServerDispatcher(
  override protected[this] val transport: Transport[Http2Frame, Http2Frame],
  service: Service[Request, Response],
  streamStats: Netty4StreamTransport.StatsReceiver
) extends Netty4DispatcherBase[Response, Request] with Closable {
  import Netty4ServerDispatcher._

  override protected[this] val log = Netty4ServerDispatcher.log
  override protected[this] val prefix =
    s"S L:${transport.localAddress} R:${transport.remoteAddress}"

  transport.onClose.onSuccess(onTransportClose)

  override def close(deadline: Time): Future[Unit] =
    goAway(GoAway.NoError, deadline)

  private[this] def newStreamTransport(id: Int): Netty4StreamTransport[Response, Request] = {
    val stream = Netty4StreamTransport.server(id, writer, streamStats)
    registerStream(id, stream)
    stream
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

  /**
   * Process a remote request onto the downstream service and write
   * its response to the remote.
   *
   * If the stream is reset, serving is canceled.
   * If serving fails, the stream is reset.
   */
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
        log.error(e, "[%s S:%d] ignoring exception", prefix, st.streamId)
    }
  }

  /**
   * Continually read from the transport, creating new streams.
   */
  override protected[this] val reading: Future[Unit] = {
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

}
