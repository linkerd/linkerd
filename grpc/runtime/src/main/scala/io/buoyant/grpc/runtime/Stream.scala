package io.buoyant.grpc.runtime

import com.twitter.concurrent.{AsyncMutex, AsyncQueue}
import com.twitter.finagle.Failure
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Activity, Event, Future, Promise, Return, Throw, Try, Var}

trait Stream[+T] {
  def recv(): Future[Stream.Releasable[T]]

  def reset(err: GrpcStatus): Unit
}

object Stream {

  val NopRelease: () => Future[Unit] = () => Future.Unit
  case class Releasable[+T](value: T, release: () => Future[Unit] = NopRelease)

  trait Provider[-T] {
    def send(t: T): Future[Unit]
    def close(): Future[Unit]
  }

  def fromQueue[T](q: AsyncQueue[Releasable[T]]): Stream[T] = new Stream[T] {
    override def reset(rst: GrpcStatus): Unit = q.fail(rst, discard = true)
    override def recv(): Future[Releasable[T]] = q.poll()
  }

  def fromSeq[T](seq: Seq[T]): Stream[T] = {
    val q = new AsyncQueue[Releasable[T]]()
    seq.foreach(t => q.offer(Releasable(t)))
    q.fail(GrpcStatus.Ok(), discard = false)
    fromQueue(q)
  }

  def value[T](v: T): Stream[T] = fromSeq(Seq(v))

  def exception[T](e: Throwable): Stream[T] = new Stream[T] {
    val eF = Future.exception(e)
    override def recv(): Future[Releasable[T]] = eF
    override def reset(rst: GrpcStatus): Unit = ()
  }

  def mk[T]: Stream[T] with Provider[T] = new Stream[T] with Provider[T] {
    // TODO bound queue? not strictly necessary if send() future observed...
    private[this] val q = new AsyncQueue[Releasable[T]]

    override def reset(e: GrpcStatus): Unit = {
      q.fail(e, discard = true)
    }

    override def recv(): Future[Releasable[T]] = q.poll()

    override def send(msg: T): Future[Unit] = {
      val p = new Promise[Unit]
      val release: () => Future[Unit] = { () =>
        p.setDone()
        Future.Unit
      }
      if (q.offer(Releasable(msg, release))) p
      else Future.exception(Failure("rejected", Failure.Rejected))
    }

    override def close(): Future[Unit] = {
      q.fail(GrpcStatus.Ok(), discard = false)
      Future.Unit
    }
  }

  def empty(status: GrpcStatus): Stream[Nothing] = new Stream[Nothing] {
    override def reset(e: GrpcStatus): Unit = ()
    override def recv(): Future[Releasable[Nothing]] = Future.exception(status)
  }

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

      private[this] var streamRef: Either[Future[Stream[T]], Try[Stream[T]]] =
        Left(streamFut)

      def reset(e: GrpcStatus): Unit = synchronized {
        streamRef match {
          case Right(_) =>
          case Left(f) =>
            f.raise(Failure(e, Failure.Interrupted))
        }
        streamRef = Right(Throw(e))
      }

      override def recv(): Future[Stream.Releasable[T]] = {
        val f = recvMu.acquireAndRun {
          synchronized {
            streamRef match {
              case Left(f) => f.transform(_recv) // first time through
              case Right(Return(s)) => s.recv()
              case Right(Throw(e)) => Future.exception(e)
            }
          }
        }

        val p = new Promise[Stream.Releasable[T]]
        f.proxyTo(p)
        p.setInterruptHandler {
          case e@Failure(cause) if e.isFlagged(Failure.Interrupted) =>
            val status = cause match {
              case Some(s: GrpcStatus) => s
              case Some(e) => GrpcStatus.Canceled(e.getMessage)
              case None => GrpcStatus.Canceled()
            }
            reset(status)
            f.raise(e)
          case e =>
            reset(GrpcStatus.Canceled(e.getMessage))
            f.raise(e)
        }
        p
      }

      private[this] val _recv: Try[Stream[T]] => Future[Stream.Releasable[T]] = { v =>
        val ret = synchronized {
          streamRef match {
            case Right(reset) =>
              // If something has happened to update this, just use
              // that update
              reset
            case Left(_) =>
              streamRef = Right(v)
              v
          }
        }
        ret match {
          case Return(s) => s.recv()
          case Throw(e) => Future.exception(e)
        }
      }
    }

}
