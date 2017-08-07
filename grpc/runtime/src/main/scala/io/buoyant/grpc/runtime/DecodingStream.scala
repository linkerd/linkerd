package io.buoyant.grpc.runtime

import com.twitter.concurrent.{AsyncMutex, Permit}
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Return, Promise, Throw, Try}
import java.nio.ByteBuffer
import scala.util.control.NoStackTrace

/**
 * Presents a T-typed [[Stream]] from an h2.Stream of HTTP/2 frames
 * and a [[Codec]]. Handles framing and buffering of partial/mutiple
 * messages.
 *
 * Like other readable interfaces, [[DecodingStream.recv]] must be
 * invoked serially, i.e. when there are no pending `recv()` calls on
 * the stream.
 */
private[runtime] trait DecodingStream[+T] extends Stream[T] {
  import DecodingStream._

  protected[this] def frames: h2.Stream
  protected[this] def decoder: ByteBuffer => T
  protected[this] def getStatus: h2.Frame.Trailers => GrpcStatus

  // convenience
  private[this]type Releasable = Stream.Releasable[T]

  /**
   * Holds HTTP/2 data that has not yet been returned from recv().
   *
   * These states hold a ByteBuffer-backed Buf, as well as a
   * `Releaser`, which manages when HTTP/2 frames are released
   * (i.e. for flow control).
   */
  private[this] var recvState: RecvState = RecvState.Empty

  /**
   * Ensures that at most one recv call is actively being processed,
   * so that read-and-update access to `recvState` is safe.
   */
  private[this] val recvMu = new AsyncMutex()

  override def reset(e: GrpcStatus): Unit = synchronized {
    recvState = RecvState.Reset(e)
  }

  /**
   * Obtain the next gRPC message from the underlying H2 stream.
   */
  override def recv(): Future[Releasable] =
    recvMu.acquireAndRun {
      // Now we have exclusive access of `recvState` until the call
      // completes. Start by trying to obtain a message directly from
      // the buffer. If a message isn't buffered, read() it from the
      // h2.Stream.
      decode(recvState, decoder) match {
        case Decoded(s, Some(msg)) => _updateBuffer(s -> Return(msg))
        case Decoded(s, None) => read(s).flatMap(_updateBuffer)
      }
    }

  /**
   * Update the receive buffer before returning the result
   * (and i.e. releasing the mutex).
   */
  private[this] val _updateBuffer: ((RecvState, Try[Releasable])) => Future[Releasable] = {
    case (s, v) =>
      recvState = s
      Future.const(v)
  }

  /**
   * Read from the h2 stream until the next message is fully buffered.
   */
  private[this] def read(s0: RecvState): Future[(RecvState, Try[Stream.Releasable[T]])] = {
    frames.read().transform {
      case Throw(rst: h2.Reset) => Future.exception(GrpcStatus.fromReset(rst))
      case Throw(e) => Future.exception(e)
      case Return(t: h2.Frame.Trailers) =>
        val status = getStatus(t)
        t.release()
        Future.exception(status)
      case Return(f: h2.Frame.Data) =>
        decodeFrame(s0, f, decoder) match {
          case Decoded(s1, Some(msg)) => Future.value(s1 -> Return(msg))
          case Decoded(s1, None) =>
            if (f.isEnd) Future.value(s1 -> Throw(GrpcStatus.Ok()))
            else read(s1)
        }
    }
  }
}

object DecodingStream {

  def apply[T](
    req: h2.Request,
    decodeF: ByteBuffer => T
  ): Stream[T] = new DecodingStream[T] {
    protected[this] val frames: h2.Stream = req.stream
    protected[this] def decoder: ByteBuffer => T = decodeF
    protected[this] val getStatus: h2.Frame.Trailers => GrpcStatus = _ => GrpcStatus.Ok()
  }

  def apply[T](
    rsp: h2.Response,
    decodeF: ByteBuffer => T
  ): Stream[T] = new DecodingStream[T] {
    protected[this] val frames: h2.Stream = rsp.stream
    protected[this] def decoder: ByteBuffer => T = decodeF
    protected[this] val getStatus: h2.Frame.Trailers => GrpcStatus = GrpcStatus.fromHeaders(_)
  }

  private sealed trait RecvState

  private object RecvState {

    case class Buffer(
      /** The current gRPC message header, if one has been parsed */
      header: Option[Header] = None,
      /** Unparsed bytes */
      buf: Buf = Buf.Empty,
      /** Tracks how many bytes are consumed and when the underling data may be released */
      releaser: Releaser = Releaser.Nil
    ) extends RecvState

    case class Reset(error: GrpcStatus) extends RecvState

    val Empty: RecvState = Buffer()
  }

  /**
   * Keeps track of when underlying h2 frames should be released.
   *
   * As bytes are read from H2 frames, they are _tracked_. As headers
   * and messages are read, bytes are _consumed_. When a message is
   * being constructed, it is paired with a _releasable_ function that
   * frees the underlying H2 frames as needed.
   */
  private trait Releaser {

    /**
     * Add the given H2 frame to be tracked and released.
     */
    def track(frame: h2.Frame): Releaser

    /**
     * Mark `n` bytes as consumed.
     */
    def consume(n: Int): Releaser

    /**
     * Reap all consumed frames into a releasable function, and return
     * the updated state afterwards.
     */
    def releasable(): (Releaser, Releaser.Func)
  }

  private object Releaser {
    type Func = () => Future[Unit]
    val NopFunc: Func = () => Future.Unit

    object Underflow
      extends Exception("released too many bytes")
      with NoStackTrace

    object Nil extends Releaser {
      override def toString = "Releaser.Nil"
      override def track(f: h2.Frame): Releaser = mk(f)

      override def consume(n: Int): Releaser =
        if (n == 0) this
        else throw Underflow

      override def releasable(): (Releaser, Func) = _releasable
      private[this] val _releasable = (this, NopFunc)
    }

    private[this] case class FrameReleaser(
      _latch: SegmentLatch,
      consumed: Int,
      total: Int,
      tail: Releaser
    ) extends Releaser {
      require(0 <= consumed && consumed <= total)

      def remaining: Int = total - consumed

      override def toString: String =
        s"Releaser.Frame($consumed, $total, $tail)"

      override def track(f: h2.Frame): Releaser =
        copy(tail = tail.track(f))

      override def consume(n: Int): Releaser =
        if (n == 0) this
        else if (n <= remaining) copy(consumed = consumed + n)
        else {
          // Leave a 0-length releasable, so that `release` is chained
          // into the tail `releasable` call.
          copy(
            consumed = total,
            tail = tail.consume(n - remaining)
          )
        }

      override def releasable(): (Releaser, Func) =
        if (consumed < total) this -> _latch.segmentReleaseFunc(last = false)
        else {
          // This frame has been entirely consumed, so return a
          // release function that releases this frame (after all
          // prior segments have been released).
          val (rest, releaseRest) = tail.releasable()
          val release = _latch.segmentReleaseFunc(last = true)
          val releaseWithRest = () => release().before(releaseRest())
          rest -> releaseWithRest
        }
    }

    /**
     * Tracks how many segments of a single frame need to be
     * released and, when all have been released, calls `underlying`
     * to release the frame.
     */
    private[this] trait SegmentLatch {

      /**
       * Obtain a release functon for a segment.
       *
       * If `last` is true, there must be no further calls to
       * `segmentReleaseFunc`.
       */
      def segmentReleaseFunc(last: Boolean): Func
    }

    private[this] object SegmentLatch {

      private[this] class Impl(underlying: Func) extends SegmentLatch {
        @volatile private[this] var segmentsToBeReleased = 0
        @volatile private[this] var allSegmentsReleasable = false

        /**
         * When each segment is released, check to determine if more
         * releases are anticipated and, if not, release
         * `underlying`.
         */
        private[this] val releaseSegment: Func = { () =>
          synchronized {
            if (segmentsToBeReleased < 1) {
              Future.exception(new IllegalStateException("releasing too many segments"))
            } else {
              segmentsToBeReleased -= 1
              if (allSegmentsReleasable && segmentsToBeReleased == 0) underlying()
              else Future.Unit
            }
          }
        }

        override def segmentReleaseFunc(last: Boolean): Func = synchronized {
          if (allSegmentsReleasable)
            throw new IllegalStateException("cannot create any further segments")

          allSegmentsReleasable = last
          segmentsToBeReleased += 1
          releaseSegment
        }
      }

      def apply(f: Func): SegmentLatch = new Impl(f)
    }

    private[this] def mk(f: h2.Frame): Releaser = f match {
      case f: h2.Frame.Trailers =>
        FrameReleaser(SegmentLatch(f.release _), 0, 0, Nil)

      case f: h2.Frame.Data =>
        FrameReleaser(SegmentLatch(f.release _), 0, f.buf.length, Nil)
    }
  }

  private[this] case class Header(compressed: Boolean, size: Int)

  /*
   * Decoders utilities
   */

  private[this] val GrpcFrameHeaderSz = Codec.GrpcFrameHeaderSz

  private def decodeHeader[T](bb0: ByteBuffer): Option[Header] =
    if (bb0.remaining < GrpcFrameHeaderSz) None
    else {
      val bb = bb0.duplicate()
      bb.limit(bb0.position + GrpcFrameHeaderSz)
      val compressed = (bb.get == 1)
      val sz = bb.getInt
      Some(Header(compressed, sz))
    }

  private case class Decoded[T](
    state: RecvState,
    result: Option[Stream.Releasable[T]]
  )

  private def decode[T](
    s0: RecvState,
    decoder: ByteBuffer => T
  ): Decoded[T] = s0 match {
    case rst@RecvState.Reset(_) => Decoded(rst, None)

    case RecvState.Buffer(Some(hdr), buf, releaser) =>
      val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
      decodeMessage(hdr, bb0.duplicate(), releaser, decoder)

    case RecvState.Buffer(None, buf, releaser) =>
      val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
      val bb = bb0.duplicate()
      decodeHeader(bb) match {
        case None => Decoded(s0, None)
        case Some(hdr) =>
          bb.position(bb.position + GrpcFrameHeaderSz)
          val r = releaser.consume(GrpcFrameHeaderSz)
          decodeMessage(hdr, bb, r, decoder)
      }
  }

  private def decodeFrame[T](
    s0: RecvState,
    frame: h2.Frame.Data,
    decoder: ByteBuffer => T
  ): Decoded[T] = s0 match {
    case rst@RecvState.Reset(_) => Decoded(rst, None)

    case RecvState.Buffer(None, initbuf, releaser0) =>
      val buf = initbuf.concat(frame.buf)
      val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
      val bb = bb0.duplicate()
      val releaser = releaser0.track(frame)
      decodeHeader(bb.duplicate()) match {
        case None => Decoded(RecvState.Buffer(None, buf, releaser), None)
        case Some(hdr) =>
          bb.position(bb.position + GrpcFrameHeaderSz)
          val r = releaser.consume(GrpcFrameHeaderSz)
          decodeMessage(hdr, bb, r, decoder)
      }

    case RecvState.Buffer(Some(hdr), initbuf, releaser) =>
      // We've already decoded a header, but not its message. Try to
      // decode the message.
      val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(initbuf.concat(frame.buf))
      decodeMessage(hdr, bb0.duplicate(), releaser.track(frame), decoder)
  }

  private def decodeMessage[T](
    hdr: Header,
    bb: ByteBuffer,
    releaser: Releaser,
    decoder: ByteBuffer => T
  ): Decoded[T] =
    if (hdr.compressed) throw new IllegalArgumentException("compression not supported yet")
    else if (hdr.size <= bb.remaining) {
      // The message is fully encoded in the buffer, so decode it.
      val end = bb.position + hdr.size
      val (nextReleaser, release) = releaser.consume(hdr.size).releasable()
      val msg = {
        val msgbb = bb.duplicate()
        msgbb.limit(end)
        val msg = decoder(msgbb)
        Some(Stream.Releasable(msg, release))
      }
      // Update the unparsed buffer to point after the parsed message.
      bb.position(end)
      Decoded(RecvState.Buffer(None, Buf.ByteBuffer.Owned(bb), nextReleaser), msg)
    } else Decoded(RecvState.Buffer(Some(hdr), Buf.ByteBuffer.Owned(bb), releaser), None)

}
