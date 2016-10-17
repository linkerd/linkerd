package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Future, Promise, Return, Throw}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace

private[h2] object Netty4Stream {

  private val log = com.twitter.logging.Logger.get(getClass.getName)

  type Releaser = Int => Future[Unit]

  private def unexpectedFrame(f: Http2StreamFrame) =
    new IllegalStateException(s"Unexpected frame: ${f.name}")

  /** A data frame that wraps an Http2DataFrame */
  private class FrameData(frame: Http2DataFrame, releaser: Releaser) extends Frame.Data {
    private[this] val windowIncrement = frame.content.readableBytes + frame.padding
    def buf = ByteBufAsBuf.Owned(frame.content)
    def isEnd = frame.isEndStream
    def release(): Future[Unit] = {
      // XXX this causes problems currently, but it seems like we
      // should release things here...
      //
      //   frame.content.release()

      releaser(windowIncrement)
    }
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
private[h2] class Netty4Stream(
  releaser: Netty4Stream.Releaser,
  minAccumFrames: Int = Int.MaxValue,
  stats: StatsReceiver = NullStatsReceiver
) extends AsyncQueueStreamer[Http2StreamFrame](minAccumFrames, stats) {

  import Netty4Stream._

  protected[this] def toFrame(in: Http2StreamFrame): Frame = in match {
    case f: Http2DataFrame =>
      new FrameData(f, releaser)

    case f: Http2HeadersFrame if f.isEndStream => // Trailers
      Netty4Message.Trailers(f.headers)

    case f => throw unexpectedFrame(f)
  }

  protected[this] def accumStream(frames: Queue[Http2StreamFrame]): StreamAccum = {
    var content: CompositeByteBuf = null
    var bytes = 0
    var dataEos = false
    var trailers: Frame.Trailers = null

    val nFrames = frames.length
    val iter = frames.iterator
    while (!dataEos && trailers == null && iter.hasNext) {
      iter.next() match {
        case f: Http2DataFrame =>
          bytes += f.content.readableBytes + f.padding
          // Initialize content using the first frame's allocator
          if (content == null) {
            content = f.content.alloc.compositeBuffer(nFrames)
          }
          content.addComponent(true /*advance widx*/ , f.content)
          dataEos = f.isEndStream

        case f: Http2HeadersFrame =>
          trailers = Netty4Message.Trailers(f.headers)

        case _ =>
      }
    }

    val data: Frame.Data =
      if (content == null) null
      else {
        new Frame.Data {
          val buf = ByteBufAsBuf.Owned(content.retain())
          def isEnd = dataEos
          def release() =
            if (bytes > 0) releaser(bytes)
            else Future.Unit
        }
      }

    mkStreamAccum(data, trailers)
  }

}
