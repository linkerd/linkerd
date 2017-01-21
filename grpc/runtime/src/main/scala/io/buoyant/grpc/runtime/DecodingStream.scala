package io.buoyant.grpc.runtime

import com.twitter.concurrent.{AsyncMutex, Permit}
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Return, Throw, Try}
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
private[runtime] class DecodingStream[+T](
  codec: Codec[T],
  frames: h2.Stream
) extends Stream[T] {
  import DecodingStream._

  // convenience
  private[this]type Releasable = Stream.Releasable[T]

  /**
   * Holds HTTP/2 data that has not yet been returned from recv().
   *
   * One of two states:
   * - Framing: when no gRPC message frame has been read
   * - Framed: with the current frame header
   *
   * These states hold a ByteBuffer-backed Buf, as well as a
   * `Releaser`, which manages when HTTP/2 frames are released
   * (i.e. for flow control).
   */
  @volatile private[this] var recvBuffer: RecvBuffer = EmptyRecvBuffer

  /**
   * Ensures that at most one recv call is actively being processed,
   * so that read-and-update access to `recvBuffer` is safe.
   */
  private[this] val recvMu = new AsyncMutex()

  /**
   * Obtain the next gRPC message from the underlying H2 stream.
   */
  override def recv(): Future[Releasable] =
    recvMu.acquireAndRun {
      decode(recvBuffer) match {
        case (rb, Some(msg)) =>
          recvBuffer = rb
          Future.value(msg)

        case (rb, None) =>
          // More data is needed to return another message
          readMessage(rb).flatMap(_updateBuffer)
      }
    }

  private[this] val _updateBuffer: ((RecvBuffer, Try[Releasable])) => Future[Releasable] = {
    case (rb, v) =>
      recvBuffer = rb
      Future.const(v)
  }

  private[this] def decode(s: RecvBuffer): (RecvBuffer, Option[Stream.Releasable[T]]) =
    s match {
      case Framed(h0, buf, releaser) =>
        val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
        decodeMessage(h0, bb0.duplicate(), releaser)

      case s => (s, None)
    }

  private[this] def readMessage(s0: RecvBuffer): Future[(RecvBuffer, Try[Stream.Releasable[T]])] = {
    frames.read().flatMap {
      case frame: h2.Frame.Data =>
        decodeFrame(s0, frame) match {
          case (s1, Some(msg)) =>
            Future.value(s1 -> Return(msg))
          case (s1, None) =>
            if (frame.isEnd) Future.value(s1 -> Throw(Stream.Closed))
            else readMessage(s1)
        }

      case frame: h2.Frame.Trailers =>
        // XXX TODO check grpc-status etc
        Future.value(s0 -> Throw(Stream.Closed))
    }
  }

  private[this] def decodeFrame(
    s0: RecvBuffer,
    frame: h2.Frame.Data
  ): (RecvBuffer, Option[Stream.Releasable[T]]) =
    s0 match {
      case Framing(initbuf, releaser0) =>
        val buf = initbuf.concat(frame.buf)
        val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
        val bb = bb0.duplicate()
        val releaser = releaser0 ++ Releaser(frame)

        if (hasHeader(bb)) {
          val h0 = decodeHeader(bb)
          bb.position(bb.position + GrpcFrameHeaderSz)
          decodeMessage(h0, bb, releaser.advance(GrpcFrameHeaderSz))
        } else (Framing(buf, releaser), None)

      case Framed(h0, initbuf, releaser) =>
        val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(initbuf.concat(frame.buf))
        decodeMessage(h0, bb0.duplicate(), releaser ++ Releaser(frame))
    }

  private[this] def decodeMessage(
    h0: Header,
    bb: ByteBuffer,
    releaser: Releaser
  ): (RecvBuffer, Option[Stream.Releasable[T]]) =
    if (h0.compressed) throw new IllegalArgumentException("compression not supported yet")
    else if (hasMessage(bb, h0.size)) {
      // Frame fully buffered.
      val end = bb.position + h0.size
      val (nextReleaser, release) = releaser.releasable(h0.size)
      val msg = {
        val msgbb = bb.duplicate()
        msgbb.limit(end)
        val msg = codec.decodeByteBuffer(msgbb)
        Some(Stream.Releasable(msg, release))
      }
      bb.position(end)
      if (hasHeader(bb)) {
        // And another header buffered...
        val h1 = decodeHeader(bb)
        bb.position(bb.position + GrpcFrameHeaderSz)
        val r = nextReleaser.advance(GrpcFrameHeaderSz)
        (Framed(h1, Buf.ByteBuffer.Owned(bb), r), msg)
      } else (Framing(Buf.ByteBuffer.Owned(bb), nextReleaser), msg)
    } else (Framed(h0, Buf.ByteBuffer.Owned(bb), releaser), None)

}

object DecodingStream {
  val GrpcFrameHeaderSz = Codec.GrpcFrameHeaderSz

  private trait Releaser {
    def ++(next: Releaser): Releaser

    def advance(n: Int): Releaser
    def releasable(n: Int): (Releaser, Releaser.Func)
  }

  private object Releaser {
    type Func = () => Future[Unit]
    val NopFunc: Func = () => Future.Unit

    class Underflow extends Exception("released too many bytes") with NoStackTrace

    object Nop extends Releaser {
      override def ++(next: Releaser): Releaser = next

      override def advance(n: Int): Releaser =
        if (n == 0) this
        else throw new Underflow

      override def releasable(n: Int): (Releaser, Func) =
        (advance(n), NopFunc)
    }

    private[this] case class Impl(
      releaseSz: Int,
      release: () => Future[Unit],
      next: Releaser
    ) extends Releaser {

      override def ++(r: Releaser): Releaser =
        copy(next = next ++ r)

      override def advance(n: Int): Releaser =
        if (n == 0) this
        else if (n >= releaseSz) {
          // Collapse to beharvested when releasable() is called.
          copy(
            releaseSz = 0,
            next = next.advance(n - releaseSz)
          )
        } else copy(releaseSz = releaseSz - n)

      override def releasable(n: Int): (Releaser, Func) =
        if (n >= releaseSz) {
          val (rest, nextRelease) = next.releasable(n - releaseSz)
          val f = () => release().before(nextRelease())
          rest -> f
        } else {
          val rest = copy(releaseSz = releaseSz - n)
          rest -> NopFunc
        }
    }

    def apply(sz: Int, f: () => Future[Unit]): Releaser =
      Impl(sz, f, Nop)

    def apply(frame: h2.Frame.Data): Releaser =
      apply(frame.buf.length, frame.release)
  }

  private case class Header(compressed: Boolean, size: Int)

  private sealed trait RecvBuffer

  private case class Framing(
    buf: Buf,
    releaser: Releaser
  ) extends RecvBuffer {
    require(buf.length < GrpcFrameHeaderSz)
  }

  private case class Framed(
    header: Header,
    rest: Buf,
    releaser: Releaser
  ) extends RecvBuffer

  private val EmptyRecvBuffer = Framing(Buf.Empty, Releaser.Nop)

  private val hasHeader: ByteBuffer => Boolean =
    bb => { GrpcFrameHeaderSz <= bb.remaining }

  private val hasMessage: (ByteBuffer, Int) => Boolean =
    (bb, msgsz) => { msgsz <= bb.remaining }

  private def decodeHeader(bb0: ByteBuffer): Header = {
    require(hasHeader(bb0))
    val bb = bb0.duplicate()
    bb.limit(bb.position + GrpcFrameHeaderSz)
    val compressed = (bb.get == 1)
    val sz = bb.getInt
    Header(compressed, sz)
  }

}
