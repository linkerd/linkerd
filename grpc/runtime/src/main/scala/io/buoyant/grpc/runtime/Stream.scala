package io.buoyant.grpc.runtime

import com.twitter.concurrent.{AsyncMutex, AsyncQueue}
import com.twitter.finagle.Failure
import com.twitter.finagle.buoyant.h2.Reset
import com.twitter.util._

trait Stream[+T] {
  /**
   * Consumers of a Stream should call recv() to poll messages off of the Stream.  Each message is
   * wrapped in a Releasable which provides a release function.  Consumers must call the release
   * function of each message once they are done with it.  If the stream has terminated gracefully,
   * this will return a Future.Exception[GrpcStatus].
   */
  def recv(): Future[Stream.Releasable[T]]

  /**
   * Reset the underlying H2 stream.  This should be called by the consumer of this stream when
   * they no longer want to receive messages.  This uses an H2 stream reset to signal to the
   * producer to stop producing.
   */
  def reset(err: Reset): Unit
}

object Stream {

  val NopRelease: () => Future[Unit] = () => Future.Unit
  case class Releasable[+T](value: T, release: () => Future[Unit] = NopRelease)

  trait Provider[-T] {
    def send(t: T): Future[Unit]
    def close(grpcStatus: GrpcStatus = GrpcStatus.Ok()): Future[Unit]
  }

  private def failAndDrainQueue[T](q: AsyncQueue[Releasable[T]], e: Throwable): Unit = {
    q.fail(e, discard = false)
    def drain(q: AsyncQueue[Releasable[T]]): Future[Nothing] = {
      q.poll().flatMap { t =>
        t.release()
        drain(q)
      }
    }
    drain(q)
    ()
  }

  def fromQueue[T](q: AsyncQueue[Releasable[T]]): Stream[T] = new Stream[T] {
    override def reset(err: Reset): Unit = {
      failAndDrainQueue(q, err)
    }
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
    override def reset(err: Reset): Unit = ()
  }

  def mk[T]: Stream[T] with Provider[T] = new Stream[T] with Provider[T] {
    // TODO bound queue? not strictly necessary if send() future observed...
    private[this] val q = new AsyncQueue[Releasable[T]]

    override def reset(e: Reset): Unit = {
      failAndDrainQueue(q, e)
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

    override def close(grpcStatus: GrpcStatus = GrpcStatus.Ok()): Future[Unit] = {
      q.fail(grpcStatus, discard = false)
      Future.Unit
    }
  }

  def empty(status: GrpcStatus): Stream[Nothing] = new Stream[Nothing] {
    override def reset(e: Reset): Unit = ()
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

      def reset(e: Reset): Unit = synchronized {
        streamRef match {
          case Right(Return(s)) => s.reset(e)
          case Right(Throw(_)) => // Do nothing.
          case Left(f) =>
            f.raise(Failure(e, Failure.Interrupted))
            // Interrupting a Future does not guarantee that it will fail.  If the stream future
            // does succeed, we should reset that stream.
            f.onSuccess(_.reset(e))
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

        val p = new Promise[Stream.Releasable[T]] with Promise.InterruptHandler {
          override protected def onInterrupt(t: Throwable): Unit =
            t match {
              case e@Failure(cause) if e.isFlagged(Failure.Interrupted) =>
                val status = cause match {
                  case Some(e: Reset) => e
                  case _ => Reset.Cancel
                }
                reset(status)
                f.raise(e)
              case _ =>
                reset(Reset.Cancel)
                f.raise(t)
            }
        }
        f.proxyTo(p)
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

  def fromFuture[T](fut: Future[T]): Stream[T] = deferred(fut.map(value))

}
