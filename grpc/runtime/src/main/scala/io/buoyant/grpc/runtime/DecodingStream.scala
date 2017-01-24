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

  /**
   * Obtain the next gRPC message from the underlying H2 stream.
   */
  override def recv(): Future[Releasable] =
    recvMu.acquireAndRun {
      // Now we have exclusive access of `recvState` until the call
      // completes. Start by trying to obtain a message directly from
      // the buffer:
      val updateF = decode(recvState, decoder) match {
        case (s, Some(msg)) => Future.value(s -> Return(msg))
        case (s, None) => read(s)
      }
      updateF.flatMap(_updateBuffer)
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
      case Throw(rst: h2.Reset) => Future.exception(Stream.Closed) // XXX FIXME
      case Throw(e) => Future.exception(e)
      case Return(t: h2.Frame.Trailers) => Future.exception(Stream.Closed) // XXX FIXME
      case Return(f: h2.Frame.Data) =>
        decodeFrame(s0, f, decoder) match {
          case (s1, Some(msg)) => Future.value(s1 -> Return(msg))
          case (s1, None) =>
            if (f.isEnd) Future.value(s1 -> Throw(Stream.Closed)) // XXX FIXME
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
  }

  def apply[T](
    rsp: h2.Response,
    decodeF: ByteBuffer => T
  ): Stream[T] = new DecodingStream[T] {
    protected[this] val frames: h2.Stream = rsp.stream
    protected[this] def decoder: ByteBuffer => T = decodeF
  }

  private sealed trait RecvState
  private object RecvState {

    case class Buffer(
      /** The current gRPC message header, if one has been parsed */
      header: Option[Header] = None,
      /** Unparsed bytes */
      buf: Buf = Buf.Empty,
      /** Tracks how many bytes are consumed and when the underling data may be released */
      releaser: Releaser = Releaser.Empty
    ) extends RecvState

    val Empty: RecvState = Buffer()

    // TODO Introduce an additional state to indicate when the stream
    // has been reset.
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

    class Underflow extends Exception("released too many bytes") with NoStackTrace

    object Empty extends Releaser {
      override def track(f: h2.Frame): Releaser = mk(f)

      override def consume(n: Int): Releaser =
        if (n == 0) this
        else throw new Underflow

      override def releasable(): (Releaser, Func) = (this, NopFunc)
    }

    private[this] case class FrameReleaser(
      remaining: Int,
      release: () => Future[Unit],
      next: Releaser
    ) extends Releaser {
      require(remaining >= 0)

      override def track(f: h2.Frame): Releaser =
        copy(next = next.track(f))

      override def consume(n: Int): Releaser =
        if (n == 0) this
        else if (n >= remaining) {
          // Leave a 0-length releasable, so that `release` is chained
          // into the next `releasable` call.
          copy(
            remaining = 0,
            next = next.consume(n - remaining)
          )
        } else copy(remaining = remaining - n)

      override def releasable(): (Releaser, Func) =
        if (remaining == 0) {
          val (rest, releaseRest) = next.releasable()
          val doRelease = () => release().before(releaseRest())
          rest -> doRelease
        } else {
          // This frame isn't yet releasable, but we require that this
          // sub-slice is released before releasing the frame:
          val p = new Promise[Unit]
          val rest = copy(release = () => p.before(release()))
          val doRelease = () => { p.setDone(); Future.Unit }
          rest -> doRelease
        }
    }

    private[this] def mk(frame: h2.Frame): Releaser = frame match {
      case f: h2.Frame.Data => FrameReleaser(f.buf.length, f.release, Empty)
      case f: h2.Frame.Trailers => FrameReleaser(0, f.release, Empty)
    }
  }

  private[this] case class Header(compressed: Boolean, size: Int)

  private[this] val GrpcFrameHeaderSz = Codec.GrpcFrameHeaderSz

  private def decode[T](
    s: RecvState,
    decoder: ByteBuffer => T
  ): (RecvState, Option[Stream.Releasable[T]]) =
    s match {
      case RecvState.Buffer(Some(hdr), buf, releaser) =>
        val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
        decodeMessage(hdr, bb0.duplicate(), releaser, decoder)

      case s => (s, None)
    }

  private def decodeFrame[T](
    s0: RecvState,
    frame: h2.Frame.Data,
    decoder: ByteBuffer => T
  ): (RecvState, Option[Stream.Releasable[T]]) =
    s0 match {
      case RecvState.Buffer(None, initbuf, releaser0) =>
        val buf = initbuf.concat(frame.buf)
        val releaser = releaser0.track(frame)

        if (buf.length >= GrpcFrameHeaderSz) {
          // The buffer has at least a frame header, so decode it and
          // try to decode the message.
          val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
          val bb = bb0.duplicate()
          val hdr = {
            val hdrbb = bb.duplicate()
            hdrbb.limit(bb.position + GrpcFrameHeaderSz)
            bb.position(bb.position + GrpcFrameHeaderSz)
            val compressed = (hdrbb.get == 1)
            val sz = hdrbb.getInt
            Header(compressed, sz)
          }
          decodeMessage(hdr, bb, releaser.consume(GrpcFrameHeaderSz), decoder)
        } else (RecvState.Buffer(None, buf, releaser), None)

      case RecvState.Buffer(Some(hdr), initbuf, releaser) =>
        // We've already decoded a header, but not its message.  Try
        // to decode the message.
        val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(initbuf.concat(frame.buf))
        decodeMessage(hdr, bb0.duplicate(), releaser.track(frame), decoder)
    }

  private def decodeMessage[T](
    hdr: Header,
    bb: ByteBuffer,
    releaser: Releaser,
    decoder: ByteBuffer => T
  ): (RecvState, Option[Stream.Releasable[T]]) =
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
      (RecvState.Buffer(None, Buf.ByteBuffer.Owned(bb), nextReleaser), msg)
    } else (RecvState.Buffer(Some(hdr), Buf.ByteBuffer.Owned(bb), releaser), None)

}
