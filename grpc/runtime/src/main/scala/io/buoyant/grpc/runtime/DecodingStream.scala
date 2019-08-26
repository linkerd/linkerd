package io.buoyant.grpc.runtime

import com.twitter.concurrent.AsyncMutex
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.buoyant.h2.Reset
import com.twitter.util.{Future, Return, Throw, Try}
import io.netty.buffer.{ByteBuf, CompositeByteBuf, Unpooled}
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
private[runtime] trait DecodingStream[T] extends Stream[T] {
  import DecodingStream._

  protected[this] def frames: h2.Stream
  protected[this] def decoder: ByteBuffer => T
  protected[this] def getStatus: h2.Frame.Trailers => GrpcStatus

  // convenience
  private[this]type Releasable = Stream.Releasable[T]

  /**
   * This state holds a `Releaser`, which manages when HTTP/2 frames
   * are released (i.e. for flow control).
   */
  private[this] var buffer: Buffer = Buffer()

  /**
   * This holds all the bytes read from data frames.
   */
  private[this] val buf: CompositeByteBuf = Unpooled.compositeBuffer()

  /**
   * Ensures that at most one recv call is actively being processed,
   * so that read-and-update access to `recvState` is safe.
   */
  private[this] val recvMu = new AsyncMutex()

  override def reset(e: Reset): Unit = frames.cancel(e)

  /**
   * Obtain the next gRPC message from the underlying H2 stream.
   */
  override def recv(): Future[Releasable] =
    recvMu.acquireAndRun {
      // Now we have exclusive access of `recvState` until the call
      // completes. Start by trying to obtain a message directly from
      // the buffer. If a message isn't buffered, read() it from the
      // h2.Stream.
      decode(buffer) match {
        case Decoded(s, Some(msg)) => _updateBuffer(s -> Return(msg))
        case Decoded(s, None) => read(s).flatMap(_updateBuffer)
      }
    }

  /**
   * Update the receive buffer before returning the result
   * (and i.e. releasing the mutex).
   */
  private[this] val _updateBuffer: ((Buffer, Try[Releasable])) => Future[Releasable] = {
    case (s, v) =>
      buffer = s
      Future.const(v)
  }

  private[this] def clearUndecodedData(state: Buffer, compositeBuf: CompositeByteBuf) = {
    // we check how many bytes we have in the composite buffer
    val bytesLeftUnconsumed = compositeBuf.readableBytes()
    // we then construct a releaser that will release the frames that have not been decoded
    state.releaser.consume(bytesLeftUnconsumed).releasable()._2()
    // and lastly we skip the unread bytes from the composite buffer and discard them
    skipAndDiscardRead(bytesLeftUnconsumed)
  }

  /**
   * Read from the h2 stream until the next message is fully buffered.
   */
  private[this] def read(s0: Buffer): Future[(Buffer, Try[Stream.Releasable[T]])] = {
    frames.read().transform {
      case Throw(rst: h2.Reset) => Future.exception(GrpcStatus.fromReset(rst))
      case Throw(e) => Future.exception(e)
      case Return(t: h2.Frame.Trailers) =>
        val status = getStatus(t)
        t.release()
        Future.exception(status)
      case Return(f: h2.Frame.Data) =>
        decodeFrame(s0, f) match {
          case Decoded(s1, Some(msg)) => Future.value(s1 -> Return(msg))
          case Decoded(s1, None) =>
            if (f.isEnd) {
              clearUndecodedData(s1, buf)
              Future.value(s1 -> Throw(GrpcStatus.Ok()))
            } else read(s1)
        }
    }
  }

  private[this] def decodeHeader(bb0: ByteBuf): Option[Header] =
    if (bb0.readableBytes() < GrpcFrameHeaderSz) None
    else {
      val compressed = bb0.readByte() == 1
      val sz = bb0.readInt()
      Some(Header(compressed, sz))
    }

  private case class Decoded(
    state: Buffer,
    result: Option[Stream.Releasable[T]]
  )

  private[this] def decode(
    s0: Buffer
  ): Decoded = s0 match {
    case Buffer(Some(hdr), releaser) =>
      decodeMessage(hdr, releaser)

    case Buffer(None, releaser) =>
      decodeHeader(buf) match {
        case None => Decoded(s0, None)
        case Some(hdr) =>
          val r = releaser.consume(GrpcFrameHeaderSz)
          decodeMessage(hdr, r)
      }
  }

  private[this] def decodeFrame(
    s0: Buffer,
    frame: h2.Frame.Data
  ): Decoded =
    if (frame.buf.readableBytes() > 0)
      s0 match {
        case Buffer(None, releaser0) =>
          // We don't want the composite buf to be able to release this buf component until the frame
          // has been released, so we call retain() here.  This component should be fully released once
          // both the frame has been released and when the composite buf has fully read and discarded
          // this component.
          buf.addComponent(true, frame.buf.retain())
          val releaser = releaser0.track(frame)
          decodeHeader(buf) match {
            case None => Decoded(Buffer(None, releaser), None)
            case Some(hdr) =>
              val r = releaser.consume(GrpcFrameHeaderSz)
              decodeMessage(hdr, r)
          }

        case Buffer(Some(hdr), releaser) =>
          // We've already decoded a header, but not its message. Try to
          // decode the message.
          buf.addComponent(true, frame.buf.retain())
          decodeMessage(hdr, releaser.track(frame))
      }
    else {
      // nothing too try to decode if frame is empty
      // so just release it right away
      frame.release()
      Decoded(s0, None)
    }

  private[this] def skipAndDiscardRead(numBytesToSkip: Int) = {
    buf.skipBytes(numBytesToSkip)
    buf.discardReadComponents()
  }

  private[this] def decodeMessage(
    hdr: Header,
    releaser: Releaser
  ): Decoded =
    if (hdr.compressed) throw new IllegalArgumentException("compression not supported yet")
    else if (hdr.size <= buf.readableBytes()) {
      // The message is fully encoded in the buffer, so decode it.
      val (nextReleaser, release) = releaser.consume(hdr.size).releasable()
      // Copy the buffered message into a nio buffer so that it can be decoded.
      val nioBuf = buf.nioBuffer(buf.readerIndex(), hdr.size)
      val msg = decoder(nioBuf)
      // Advance the reader index past the message and release any fully read components.
      skipAndDiscardRead(hdr.size)
      Decoded(Buffer(None, nextReleaser), Some(Stream.Releasable(msg, release)))
    } else Decoded(Buffer(Some(hdr), releaser), None)
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

  private case class Buffer(
    /** The current gRPC message header, if one has been parsed */
    header: Option[Header] = None,
    /** Tracks how many bytes are consumed and when the underling data may be released */
    releaser: Releaser = Releaser.Nil
  )

  private case class Header(compressed: Boolean, size: Int)

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
        FrameReleaser(SegmentLatch(f.release _), 0, f.buf.readableBytes(), Nil)
    }
  }

  private val GrpcFrameHeaderSz = Codec.GrpcFrameHeaderSz
}
