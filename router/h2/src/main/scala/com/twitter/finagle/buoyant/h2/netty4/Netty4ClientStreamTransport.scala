package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.handler.codec.http2.{Http2StreamFrame, Http2HeadersFrame}

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

  val closeP = new Promise[Unit]
  def onClose: Future[Unit] = closeP

  private[this] def setRequestFinished(): Unit = {
    isRequestFinished = true
    if (isClosed) {
      val _ = closeP.setDone()
    }
  }

  private[this] def setResponseFinished(): Unit = {
    isResponseFinished = true
    if (isClosed) {
      val _ = closeP.setDone()
    }
  }

  def offer(f: Http2StreamFrame): Boolean = recvq.offer(f)

  def writeHeaders(hdrs: Headers, eos: Boolean = false) = {
    val tx = transport.write(streamId, hdrs, eos)
    if (eos) tx.ensure(setRequestFinished())
    tx
  }

  /** Write a request stream */
  def writeStream(data: DataStream): Future[Unit] = {
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

  private[this] val writeData: DataStream.Frame => Future[Boolean] = { v =>
    val writeF = v match {
      case data: DataStream.Data =>
        transport.write(streamId, data).before(data.release()).map(_ => data.isEnd)
      case tlrs: DataStream.Trailers =>
        transport.write(streamId, tlrs).before(Future.True)
    }
    if (v.isEnd) writeF.ensure(setRequestFinished())
    writeF
  }

  def readResponse(): Future[Response] = {
    require(!isResponseFinished)
    // Start out by reading response headers from the stream
    // queue. Once a response is initialized, if data is expected,
    // continue reading from the queue until an end stream message is
    // encounetered.
    recvq.poll().map {
      case f: Http2HeadersFrame if f.isEndStream =>
        setResponseFinished()
        Netty4Message.Response(f.headers, DataStream.Nil)

      case f: Http2HeadersFrame =>
        val responseStart = Stopwatch.start()
        val rsp = Netty4Message.Response(f.headers, newDataStream())
        rsp.onEnd.ensure(setResponseFinished())
        rsp

      case f =>
        setResponseFinished()
        throw new IllegalArgumentException(s"Expected response HEADERS; received ${f.name}")
    }
  }

  protected[this] def newDataStream(): DataStream =
    new Netty4DataStream(releaser, minAccumFrames, recvq, statsReceiver)

  protected[this] val releaser: Int => Future[Unit] = { incr =>
    transport.updateWindow(streamId, incr)
  }
}
