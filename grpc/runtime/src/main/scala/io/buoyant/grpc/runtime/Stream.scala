package io.buoyant.grpc.runtime

import com.twitter.concurrent.{AsyncMutex, AsyncQueue}
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Return, Throw, Try}

trait Stream[+T] {
  def recv(): Future[Stream.Releasable[T]]

  // TODO support grpc error types
  // def reset(err: Grpc.Error): Unit
}

object Stream {

  val NopRelease: () => Future[Unit] = () => Future.Unit
  case class Releasable[+T](value: T, release: () => Future[Unit] = NopRelease)

  trait Provider[-T] {
    def send(t: T): Future[Unit]

    def close(): Future[Unit]
  }

  def apply[T](): Stream[T] with Provider[T] = new Stream[T] with Provider[T] {
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

  /**
   * A stream backed by a promised stream.
   */
  def deferred[T](streamFut: Future[Stream[T]]): Stream[T] =
    new Stream[T] {
      // What happens to a stream deferred?
      //
      // Does it dry up
      // Like a raisin in the sun?
      //
      // Or fester like a sore--
      // And then run?
      //
      // Does it stink like rotten meat?
      // Or crust and sugar over--
      // like a syrupy sweet?
      //
      // Maybe it just sags
      // like a heavy load.
      //
      // Or does it explode?

      // A mutex is used to ensure first-caller gets the first frame
      // (which isn't guaranteed without it).
      private[this] val recvMu = new AsyncMutex()

      @volatile private[this] var streamRef: Either[Future[Stream[T]], Try[Stream[T]]] =
        Left(streamFut)

      private[this] val _recv: Try[Stream[T]] => Future[Stream.Releasable[T]] = { v =>
        streamRef = Right(v)
        v match {
          case Return(stream) => stream.recv()
          case Throw(e) => Future.exception(e)
        }
      }

      override def recv(): Future[Stream.Releasable[T]] =
        recvMu.acquireAndRun {
          streamRef match {
            case Left(f) => f.transform(_recv)
            case Right(Return(stream)) => stream.recv()
            case Right(Throw(e)) => Future.exception(e)
          }
        }
    }
}
