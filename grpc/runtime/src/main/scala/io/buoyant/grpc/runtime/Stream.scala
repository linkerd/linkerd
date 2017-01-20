package io.buoyant.grpc.runtime

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Return, Throw, Try}
import java.util.concurrent.atomic.AtomicBoolean

trait Stream[+T] {
  def recv(): Future[Stream.Releasable[T]]

  // TODO support grpc error types
  // def reset(err: Grpc.Error): Unit
}

object Stream {

  case class Releasable[+T](value: T, release: () => Future[Unit])

  trait Tx[-T] {
    def send(t: T): Future[Unit]

    def close(): Future[Unit]
  }

  def apply[T](): Stream[T] with Tx[T] = new Stream[T] with Tx[T] {
    // TODO bound queue? not strictly necessary if send() future observed...
    private[this] val q = new AsyncQueue[Releasable[T]]

    override def recv(): Future[Releasable[T]] = q.poll()

    override def send(msg: T): Future[Unit] = {
      val p = new Promise[Unit]
      val release: () => Future[Unit] = { () =>
        p.setDone()
        Future.Unit
      }
      if (q.offer(Releasable(msg, release))) p
      else Future.exception(Rejected)
    }

    override def close(): Future[Unit] = {
      q.fail(Closed, discard = false)
      Future.Unit
    }
  }

  object Closed extends Throwable
  object Rejected extends Throwable

  private sealed trait DeferredState[+T]
  private object DeferredState {
    case class Waiting[T](future: Future[Stream[T]]) extends DeferredState[T]
    case class Streaming[T](stream: Stream[T]) extends DeferredState[T]
    case class Failed(thrown: Future[Nothing]) extends DeferredState[Nothing]
  }

  /**
   * A stream dependent on a not-yet-satisfied stream.
   */
  def deferred[T](streamF: Future[Stream[T]]): Stream[T] = new Stream[T] {
    @volatile private[this] var state: DeferredState[T] = DeferredState.Waiting(streamF)
    private[this] val recving = new AtomicBoolean(false)

    override def recv(): Future[Stream.Releasable[T]] =
      if (recving.compareAndSet(false, true)) {
        state match {
          case DeferredState.Failed(fe) =>
            recving.set(false)
            fe

          case DeferredState.Waiting(f) =>
            f.flatMap(_andRecv).transform(_markRecvd)

          case DeferredState.Streaming(stream) =>
            stream.recv().transform(_markRecvd)
        }
      } else throw new IllegalStateException("recv() may not be called concurrently")

    private[this] val _andRecv: Stream[T] => Future[Stream.Releasable[T]] = _.recv()
    private[this] val _markRecvd: Try[Stream.Releasable[T]] => Future[Stream.Releasable[T]] = { v =>
      v match {
        case Return(_) =>
        case Throw(e) =>
          state = DeferredState.Failed(Future.exception(e))
      }
      recving.set(false)
      Future.const(v)
    }

  }
}
