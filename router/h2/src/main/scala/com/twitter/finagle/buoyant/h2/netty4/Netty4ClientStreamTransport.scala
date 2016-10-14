package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.handler.codec.http2.{Http2StreamFrame, Http2HeadersFrame}

/**
 * TODO how should we go about resetting streams/transports?
 */
private[h2] class Netty4ClientStreamTransport(
  val streamId: Int,
  transport: H2Transport.Writer,
  minAccumFrames: Int,
  statsReceiver: StatsReceiver = NullStatsReceiver
) {
  require(minAccumFrames >= 2)

  private[this] val recvq = new AsyncQueue[Http2StreamFrame]

  @volatile private[this] var isRequestFinished, isResponseFinished = false
  def isClosed = isRequestFinished && isResponseFinished

  private[this] val closeP = new Promise[Unit]
  def onClose: Future[Unit] = closeP

  private[this] def setRequestFinished(): Unit = {
    isRequestFinished = true
    if (isClosed) { closeP.setDone(); () }
  }

  private[this] def setResponseFinished(): Unit = {
    isResponseFinished = true
    if (isClosed) { closeP.setDone(); () }
  }

  private[this] val responseP = new Promise[Response]

  private[this] var responseWriter: Stream.Writer[Http2StreamFrame] = null
  val response: Future[Response] = responseP

  /**
   * Frames are read from the transport by the ClientDispatcher and
   * written to the stream to be sent upstream.
   *
   * If an initial Headers frame is offered, the reponse future is satisfied.
   */
  def offerResponseFrame(frame: Http2StreamFrame): Boolean =
    if (isResponseFinished) false
    else synchronized {
      responseWriter match {
        case null =>
          frame match {
            case frame: Http2HeadersFrame =>
              val stream =
                if (frame.isEndStream) {
                  setResponseFinished()
                  Stream.Nil
                } else {
                  val stream = newStream()
                  stream.onEnd.ensure(setResponseFinished())
                  responseWriter = stream
                  stream
                }
              val rsp = Netty4Message.Response(frame.headers, stream)
              responseP.updateIfEmpty(Return(rsp))
              true

            case _ => false
          }

        case stream =>
          stream.write(frame)
      }
    }

  def writeHeaders(hdrs: Headers, eos: Boolean = false) = {
    val tx = transport.write(streamId, hdrs, eos)
    if (eos) tx.ensure(setRequestFinished())
    tx
  }

  /** Write a request stream to the underlying transport */
  def writeStream(data: Stream.Reader): Future[Unit] = {
    require(!isRequestFinished)
    if (data.isEmpty) Future.Unit
    else {
      lazy val loop: Boolean => Future[Unit] = {
        case true => Future.Unit // eos
        case false => data.read().flatMap(writeData).flatMap(loop)
      }
      data.read().flatMap(writeData).flatMap(loop)
    }
  }

  private[this] val writeData: Frame => Future[Boolean] = { v =>
    val writeF = v match {
      case data: Frame.Data =>
        transport.write(streamId, data).before(data.release()).map(_ => data.isEnd)
      case tlrs: Frame.Trailers =>
        transport.write(streamId, tlrs).before(Future.True)
    }
    if (v.isEnd) writeF.ensure(setRequestFinished())
    writeF
  }

  private[this] def newStream(): Stream.Reader with Stream.Writer[Http2StreamFrame] =
    new Netty4Stream(releaser, minAccumFrames, statsReceiver)

  private[this] val releaser: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}
