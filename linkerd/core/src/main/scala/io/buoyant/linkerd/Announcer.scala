package io.buoyant.linkerd

import com.twitter.finagle.{Announcement, Failure, Path}
import com.twitter.util.{Closable, Future, Return, Throw, Time}
import java.util.concurrent.atomic.AtomicReference
import java.net.InetSocketAddress

abstract class Announcer {
  def scheme: String
  def announce(addr: InetSocketAddress, name: Path): Closable
}

abstract class FutureAnnouncer extends Announcer {

  protected def announceAsync(addr: InetSocketAddress, name: Path): Future[Announcement]

  final def announce(addr: InetSocketAddress, name: Path): Closable = {
    val pending = announceAsync(addr, name)

    // If we close before the announcer registers, we
    // cancel the registration. If we close after
    // registration, we close it.
    @volatile var deadline: Option[Time] = None
    val closeRef = new AtomicReference[Closable](Closable.make { d =>
      deadline = Some(d)
      pending.raise(Failure("closed", Failure.Interrupted))
      Future.Unit
    })
    pending.respond {
      case Throw(_) => closeRef.set(Closable.nop)
      case Return(announced) =>
        deadline match {
          case None => closeRef.set(announced)
          case Some(d) =>
            val _ = announced.close(d)
        }
    }

    Closable.ref(closeRef)
  }
}
