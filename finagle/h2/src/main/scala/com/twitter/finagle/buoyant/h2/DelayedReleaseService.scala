package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.client.StackClient
import com.twitter.finagle.util.AsyncLatch
import com.twitter.finagle._
import com.twitter.util.{Future, Promise, Return, Time}
import java.util.concurrent.atomic.AtomicBoolean

object DelayedRelease {
  val role = StackClient.Role.prepConn
  val description = "Prevents an H2 service from being closed until its response stream completes"
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = DelayedRelease.role
      val description = DelayedRelease.description
      def make(next: ServiceFactory[Request, Response]) = next.map(new DelayedReleaseService(_))
    }
}

class DelayedReleaseService(
  service: Service[Request, Response]
) extends ServiceProxy[Request, Response](service) {

  protected[this] val latch = new AsyncLatch

  private[this] def releaseOnEnd(req: Request, rsp: Response): Unit = {
    val released = new AtomicBoolean(false)
    // wait until both Request and Response streams are complete
    val _ = Future.join(req.stream.onEnd, rsp.stream.onEnd).respond { _ =>
      if (released.compareAndSet(false, true)) {
        val _ = latch.decr()
      }
    }
  }

  override def apply(req: Request): Future[Response] = {
    latch.incr()
    service(req) transform {
      case Return(rsp) =>
        releaseOnEnd(req, rsp)
        Future.value(rsp)
      case t =>
        latch.decr()
        Future.const(t)
    }
  }

  override final def close(deadline: Time): Future[Unit] = {
    val p = new Promise[Unit]
    latch.await {
      p.become(service.close(deadline))
    }
    p
  }
}
