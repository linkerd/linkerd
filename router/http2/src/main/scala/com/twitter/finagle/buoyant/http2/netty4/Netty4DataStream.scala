package com.twitter.finagle.buoyant.http2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import scala.collection.immutable.Queue
import java.util.concurrent.atomic.AtomicReference

private[http2] object Netty4DataStream {

  type DataReleaser = Int => Future[Unit]

  private trait State
  private object Open extends State
  private case class Closing(trailers: DataStream.Trailers) extends State
  private object Closed extends State

  private val closedException =
    new IllegalStateException("stream closed")

  private val noTrailersException =
    new IllegalStateException("closing state without trailers")

  private def expectedOpenException(state: State) =
    new IllegalStateException(s"expected Open state; found ${state}")

  private def unexpectedFrame(f: Http2StreamFrame) =
    new IllegalStateException(s"Unexpected frame: ${f.name}")

  private class FrameData(frame: Http2DataFrame, releaser: DataReleaser) extends DataStream.Data {
    private[this] val windowIncrement = frame.content.readableBytes + frame.padding
    def buf = ByteBufAsBuf.Owned(frame.content)
    def isEnd = frame.isEndStream
    def release(): Future[Unit] = releaser(windowIncrement)
  }
}

/**
 * An Http2 Data stream that serves values from a queue of
 * Http2StreamFrames.
 *
 * Useful when reading a Message from a Transport. The transport can
 * just put data & trailer frames on the queue without processing.
 *
 * Data frames may be combined if there are at least `minAccumFrames`
 * pending in the queue. This allows the queue to be flushed more
 * quickly as it backs up. By default, frames are not accumulated.
 */
private[http2] class Netty4DataStream(
  frameq: AsyncQueue[Http2StreamFrame],
  releaser: Netty4DataStream.DataReleaser,
  minAccumFrames: Int = Int.MaxValue,
  stats: StatsReceiver = NullStatsReceiver
) extends DataStream {

  import Netty4DataStream._

  private[this] val accumBytes = stats.stat("accum_bytes")
  private[this] val accumMicros = stats.stat("accum_us")
  private[this] val readQlens = stats.stat("read_qlen")
  private[this] val readMicros = stats.stat("read_us")

  private[this] val state: AtomicReference[State] = new AtomicReference(Open)

  private[this] val endP = new Promise[Unit]
  def onEnd: Future[Unit] = endP

  def isEmpty = state.get == Closed

  def fail(exn: Throwable): Unit = state.get match {
    case Closed =>
    case s if state.compareAndSet(s, Closed) =>
      frameq.fail(exn, discard = true)
      endP.setException(exn)
    case _ =>
  }

  def read(): Future[DataStream.Frame] = {
    val start = Stopwatch.start()
    val f = state.get match {
      case Open =>
        val sz = frameq.size
        readQlens.add(sz)
        if (sz < minAccumFrames) frameq.poll().map(andDrainAccum)
        else Future.const(frameq.drain()).map(accumStream)

      case Closed => Future.exception(closedException)

      case closing@Closing(trailers) =>
        if (state.compareAndSet(closing, Closed)) {
          frameq.fail(closedException)
          endP.setDone()
          Future.value(trailers)
        } else Future.exception(closedException)
    }
    f.onSuccess(_ => readMicros.add(start().inMicroseconds))
    f
  }

  // Try to read as much data as is available so that it may be
  // chunked. This is done after poll() returns because it's likely
  // that multiple items may have entered the queue.
  private[this] val andDrainAccum: Http2StreamFrame => DataStream.Frame = {
    case f: Http2HeadersFrame if f.isEndStream =>
      if (state.compareAndSet(Open, Closed)) {
        endP.setDone()
        Netty4Message.Trailers(f.headers)
      } else throw expectedOpenException(state.get)

    case f: Http2DataFrame if f.isEndStream =>
      if (state.compareAndSet(Open, Closed)) {
        endP.setDone()
        toData(f)
      } else throw expectedOpenException(state.get)

    case f: Http2DataFrame if (frameq.size + 1) < minAccumFrames => toData(f)

    case f: Http2DataFrame =>
      frameq.drain() match {
        case Throw(_) => toData(f)
        case Return(q) => accumStream(f +: q)
      }

    case f => throw unexpectedFrame(f)
  }

  private def toData(f: Http2DataFrame): DataStream.Data =
    new FrameData(f, releaser)

  private val accumStream: Queue[Http2StreamFrame] => DataStream.Frame = { frames =>
    require(frames.nonEmpty)
    val start = Stopwatch.start()

    var content: CompositeByteBuf = null
    var bytes = 0
    var eos = false
    var trailers: DataStream.Trailers = null

    var iter = frames.iterator
    while (iter.hasNext && !eos && trailers == null) {
      iter.next() match {
        case f: Http2DataFrame =>
          bytes += f.content.readableBytes + f.padding
          if (content == null) {
            content = f.content.alloc.compositeBuffer(frames.length)
          }
          content.addComponent(true /*advance widx*/ , f.content)
          eos = f.isEndStream

        case f: Http2HeadersFrame =>
          trailers = Netty4Message.Trailers(f.headers)
      }
    }

    val data: DataStream.Data =
      if (content == null) null
      else {
        accumBytes.add(bytes)
        new DataStream.Data {
          val buf = ByteBufAsBuf.Owned(content.retain())
          def isEnd = eos
          def release() = if (bytes > 0) releaser(bytes) else Future.Unit
        }
      }

    val next = (data, trailers) match {
      case (null, null) => throw new IllegalStateException("No data or trailers available")
      case (data, null) => data
      case (null, tlrs) => tlrs
      case (data, tlrs) =>
        if (state.compareAndSet(Open, Closing(tlrs))) data
        else throw expectedOpenException(state.get)
    }

    accumMicros.add(start().inMicroseconds)

    next
  }

}
