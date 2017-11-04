package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncMutex
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Service, Status => SvcStatus}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util._
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.AtomicInteger
import com.twitter.finagle.buoyant.h2.netty4.Netty4StreamTransport.log

import com.twitter.finagle.util.DefaultTimer

object Netty4ClientDispatcher {
  private val log = Logger.get("h2")
  private val BaseStreamId = 3 // ID=1 is reserved for HTTP/1 upgrade
  private val MaxStreamId = (math.pow(2, 31) - 1).toInt
}

/**
 * A dispatcher that exposes a `Service[Request, Response]`.
 *
 * Requests are issued onto the dispatcher to be written on a (new)
 * remote stream, and the response is returned and streamed upstream
 * from the remote.
 */
class Netty4ClientDispatcher(
  override protected[this] val transport: Transport[Http2Frame, Http2Frame],
  protected[this] val stats: StatsReceiver,
  override protected[this] val timer: Timer = DefaultTimer
) extends Service[Request, Response] with Netty4DispatcherBase[Request, Response] {
  import Netty4ClientDispatcher._

  override protected[this] val log = Netty4ClientDispatcher.log

  override protected[this] val prefix =
    s"C L:${transport.context.localAddress} R:${transport.context.remoteAddress}"

  private[this] val streamStats = new Netty4StreamTransport.StatsReceiver(stats)

  transport.context.onClose.onSuccess(onTransportClose)

  override def close(deadline: Time): Future[Unit] = {
    streamsGauge.remove()
    goAway(GoAway.NoError, deadline)
  }

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

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStreamTransport(onServerReset: Future[Unit]): Netty4StreamTransport[Request, Response] = {
    val id = nextId()
    val stream = Netty4StreamTransport.client(id, writer, streamStats, onServerReset)
    registerStream(id, stream)
    stream
  }

  override def status: SvcStatus =
    if (isClosed) SvcStatus.Closed
    else SvcStatus.Open

  /**
   * Continually read frames from the HTTP2 transport. Demultiplex
   * frames from the transport onto a per-stream receive queue.
   */
  override protected[this] val demuxing = demux()

  override protected[this] def demuxNewStream(f: Http2StreamFrame): Future[Unit] = {
    val e = new IllegalArgumentException(s"unexpected frame on new stream: ${f.name}")
    goAway(GoAway.ProtocolError).before(Future.exception(e))
  }

  /*
   * In order to ensure that the initial frame for each stream is written to the
   * transport in order of its stream id, we hold a mutex from when the stream id
   * is allocated to when the initial frame of that stream is written.
   */
  val mutex = new AsyncMutex()

  /**
   * Write a request on the underlying connection and return its
   * response when it is received.
   */
  override def apply(req: Request): Future[Response] = {
    val onServerFailure = req.onFail
    mutex.acquire().flatMap { permit =>

      val st = newStreamTransport(onServerFailure)
      // Stream the request while receiving the response and
      // continue streaming the request until it is complete,
      // canceled,  or the response fails.
      val sendFF = st.send(req)

      // If the stream is reset prematurely, cancel the pending write
      st.onReset.onFailure {
        case StreamError.Remote(rst: Reset) => sendFF.flatten.raise(rst)
        case StreamError.Remote(e) => sendFF.flatten.raise(Reset.Cancel)
        case e => sendFF.flatten.raise(e)
      }

      sendFF.ensure(permit.release())
      sendFF.unit.before(st.onRecvMessage)
    }
  }

}
