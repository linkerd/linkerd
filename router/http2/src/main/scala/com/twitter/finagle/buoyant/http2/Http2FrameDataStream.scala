package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.util.{Future, Promise, Return, Throw}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import scala.collection.immutable.Queue
import java.util.concurrent.atomic.AtomicReference

private[http2] object Http2FrameDataStream {

  type DataReleaser = Http2DataFrame => Future[Unit]

  private trait State
  private object Open extends State
  private case class Closing(trailers: DataStream.Trailers) extends State
  private object Closed extends State

  val closedException = new IllegalStateException("stream closed")
  val noTrailersException = new IllegalStateException("closing state without trailers")
  def expectedOpenException(state: State) =
    new IllegalStateException(s"expected Open state; found ${state}")

  private case class StreamAccum(
    data: Option[DataStream.Data],
    trailers: Option[DataStream.Value]
  ) {
    require(data.nonEmpty || trailers.nonEmpty)
  }

  private class FrameData(frame: Http2DataFrame, releaser: DataReleaser)
    extends DataStream.Data {
    def buf = ByteBufAsBuf.Owned(frame.content /*.retain()*/ )
    def isEnd = frame.isEndStream
    def release(): Future[Unit] = releaser(frame)
  }

}

private[http2] class Http2FrameDataStream(
  fq: AsyncQueue[Http2StreamFrame],
  releaser: Http2FrameDataStream.DataReleaser
) extends DataStream {

  import Http2FrameDataStream._

  private[this] val state: AtomicReference[State] =
    new AtomicReference(Open)

  private def toData(f: Http2DataFrame): DataStream.Data =
    new FrameData(f, releaser)

  def read(): Future[DataStream.Value] = state.get match {
    case Open =>
      if (fq.size == 0) fq.poll().map(andAccumDrain)
      else Future.const(fq.drain()).map(accumStream)

    case Closed => Future.exception(closedException)

    case closing@Closing(trailers) =>
      if (state.compareAndSet(closing, Closed)) Future.value(trailers)
      else Future.exception(closedException)
  }

  // Try to read as much data as is available so that it may be
  // chunked. This is done after poll() returns because it's likely
  // that multiple items may have entered the queue.
  private[this] val andAccumDrain: Http2StreamFrame => DataStream.Value = {
    case f: Http2HeadersFrame if f.isEndStream =>
      if (state.compareAndSet(Open, Closed)) DataStream.Trailers(Headers(f.headers))
      else throw expectedOpenException(state.get)

    case f: Http2DataFrame if f.isEndStream =>
      if (state.compareAndSet(Open, Closed)) toData(f)
      else throw expectedOpenException(state.get)

    case f: Http2DataFrame if fq.size == 0 =>
      toData(f)

    case f: Http2DataFrame =>
      fq.drain() match {
        case Throw(_) => toData(f)
        case Return(q) => accumStream(f +: q)
      }

    case f =>
      throw new IllegalArgumentException("Unexpected frame: ${f.name}")
  }

  private val accumStream: Queue[Http2StreamFrame] => DataStream.Value = { frames =>
    var content: CompositeByteBuf = null
    var eos = false
    var trailers: Option[DataStream.Trailers] = None

    val iter = frames.iterator
    while (iter.hasNext && !eos && trailers == null) {
      iter.next() match {
        case f: Http2DataFrame =>
          if (content == null) {
            content = f.content.alloc.compositeBuffer(frames.length)
          }
          content.addComponent(true, f.content)
          eos = f.isEndStream

        case f: Http2HeadersFrame =>
          trailers = Some(DataStream.Trailers(Headers(f.headers)))
      }
    }

    val data: Option[DataStream.Data] =
      if (content == null) None
      else {
        val data = new DataStream.Data {
          val buf = ByteBufAsBuf.Owned(content)
          def isEnd = eos
          def release() = releaseq(frames)
        }
        Some(data)
      }

    (data, trailers) match {
      case (Some(data), Some(trailers)) =>
        if (state.compareAndSet(Open, Closing(trailers))) data
        else throw expectedOpenException(state.get)
      case (Some(data), None) => data
      case (None, Some(trailers)) => trailers
      case (None, None) =>
        throw new IllegalStateException("No data or trailers available")

    }
  }

  private[this] def releaseq(q: Queue[Http2StreamFrame]): Future[Unit] =
    if (q.isEmpty) Future.Unit
    else q.dequeue match {
      case (f: Http2DataFrame, tl) =>
        val released = releaser(f)
        if (tl.isEmpty) released
        else released.before(releaseq(tl))
      case (_, tl) =>
        if (tl.isEmpty) Future.Unit
        else releaseq(tl)
    }

  def fail(exn: Throwable): Future[Unit] = {
    fq.fail(exn, discard = true)
    Future.Unit
  }
}
