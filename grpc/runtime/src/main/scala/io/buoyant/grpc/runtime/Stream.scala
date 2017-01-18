package io.buoyant.grpc.runtime

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Return, Throw}
import io.buoyant.grpc.GrpcError

/**
 *
 */
trait Stream[+T] {
  /**
   *
   */
  def recv(): Future[Stream.Releasable[T]]

  /**
   * Question: Pinning this and Tx.reset together works but should we split this up into a Reader/Writer pair?
   */
  def reset(reason: GrpcError): Future[Unit]
}

object Stream {

  case class Releasable[+T](value: T, onRelease: () => Future[Unit])
  case class Errored(reason: GrpcError)

  trait Tx[-T] {
    def send(t: T): Future[Unit]

    def close(): Future[Unit]

    /**
     * Fuses this stream shut with a {{reason}}.
     *
     * Once this method is called, no more entries can be written and all unread messages are dropped.
     */
    def reset(reason: GrpcError): Future[Unit]
  }

  /**
   * Constructs a new Stream
   */
  def apply[T](): Stream[T] with Tx[T] = new Stream[T] with Tx[T] {
    // TODO bound queue? not strictly necessary if send() future observed...
    private[this] val q = new AsyncQueue[Releasable[T]]

    override def recv(): Future[Releasable[T]] = q.poll()

    override def send(msg: T): Future[Unit] = {
      val p = new Promise[Unit]
      val onRelease: () => Future[Unit] = { () =>
        p.setDone()
        Future.Unit
      }
      if (q.offer(Releasable(msg, onRelease))) p
      else Future.exception(Rejected)
    }

    override def close(): Future[Unit] = {
      q.fail(Closed, discard = false)
      Future.Unit
    }

    override def reset(err: GrpcError): Future[Unit] = {
      q.fail(RejectedWithReason(err), discard = true)
      Future.Unit
    }
  }

  object Closed extends Throwable
  object Rejected extends Throwable
  case class RejectedWithReason(reason: GrpcError) extends Throwable
}
