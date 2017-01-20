package io.buoyant.grpc.runtime

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise, Return, Throw}

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

  def async[T](streamF: Future[Stream[T]]): Stream[T] = new Stream[T] {
    private[this] val _mapRecv: Stream[T] => Future[Stream.Releasable[T]] = _.recv()
    override def recv(): Future[Stream.Releasable[T]] = streamF.flatMap(_mapRecv)
  }
}
