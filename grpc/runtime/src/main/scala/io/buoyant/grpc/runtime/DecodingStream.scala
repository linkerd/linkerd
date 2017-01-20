package io.buoyant.grpc.runtime

import com.twitter.concurrent.{AsyncMutex, Permit}
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Return, Throw, Try}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NoStackTrace

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

  private sealed trait FrameState

  private case class Framing(
    buf: Buf,
    releaser: Releaser
  ) extends FrameState {
    require(buf.length < GrpcFrameHeaderSz)
  }

  private case class Framed(
    header: Header,
    rest: Buf,
    releaser: Releaser
  ) extends FrameState

  private val InitFramingState = Framing(Buf.Empty, Releaser.Nop)

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

class DecodingStream[+T](
  codec: Codec[T],
  frames: h2.Stream
) extends Stream[T] {
  import DecodingStream._

  @volatile private[this] var state: FrameState = InitFramingState
  private[this] val recving = new AtomicBoolean(false)

  /** Must be called serially */
  override def recv(): Future[Stream.Releasable[T]] =
    if (recving.compareAndSet(false, true)) {
      decode(state) match {
        case (s, Some(msg)) =>
          state = s
          recving.set(false)
          Future.value(msg)

        case (s0, None) =>
          // More data is needed to return another message
          readMessage(s0).transform {
            case Return((s1, Return(msg))) =>
              state = s1
              recving.set(false)
              Future.value(msg)
            case Return((s1, Throw(e))) =>
              state = s1
              recving.set(false)
              Future.exception(e)
            case Throw(e) =>
              recving.set(false)
              Future.exception(e)
          }
      }
    } else throw new IllegalStateException("recv() may not be called concurrently")

  private[this] def decode(s: FrameState): (FrameState, Option[Stream.Releasable[T]]) =
    s match {
      case Framed(h0, buf, releaser) =>
        val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
        decodeMessage(h0, bb0.duplicate(), releaser)

      case s => (s, None)
    }

  /** Must be called serially, enforced by `mutex` and _recv. */
  private[this] def readMessage(s0: FrameState): Future[(FrameState, Try[Stream.Releasable[T]])] = {
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
    s0: FrameState,
    frame: h2.Frame.Data
  ): (FrameState, Option[Stream.Releasable[T]]) =
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
  ): (FrameState, Option[Stream.Releasable[T]]) =
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
