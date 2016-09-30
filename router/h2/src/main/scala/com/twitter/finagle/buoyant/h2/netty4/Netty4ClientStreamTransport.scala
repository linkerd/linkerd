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

  def offer(f: Http2StreamFrame): Boolean = recvq.offer(f)

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

  /** Write a response from the underlying transport */
  def readResponse(): Future[Response] = {
    require(!isResponseFinished)
    recvq.poll().map(frameToResponse)
  }

  // Start out by reading response headers from the stream
  // queue. Once a response is initialized, if data is expected,
  // continue reading from the queue until an end stream message is
  // encounetered.
  private[this] val frameToResponse: Http2StreamFrame => Response = {
    case f: Http2HeadersFrame if f.isEndStream =>
      setResponseFinished()
      Netty4Message.Response(f.headers, Stream.Nil)

    case f: Http2HeadersFrame =>
      val rsp = Netty4Message.Response(f.headers, newStream())
      rsp.data.onEnd.ensure(setResponseFinished())
      rsp

    case f =>
      setResponseFinished()
      throw new IllegalArgumentException(s"Expected response HEADERS; received ${f.name}")
  }

  protected[this] def newStream(): Stream =
    new Netty4Stream(releaser, minAccumFrames, recvq, statsReceiver)

  protected[this] val releaser: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}
