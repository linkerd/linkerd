package io.buoyant.namerd.storage

import com.twitter.conversions.time._
import com.twitter.finagle.Dtab
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException}
import io.buoyant.namerd.{DtabStore, Ns, VersionedDtab}
import java.nio.ByteBuffer

/**
 * A toy DtabStore that stores all state in memory.
 */
class InMemoryDtabStore(namespaces: Map[String, Dtab]) extends DtabStore {
  private[this] val dtabStatesMu = new {}
  private[InMemoryDtabStore]type DtabState = Option[VersionedDtab]
  private[InMemoryDtabStore]type DtabStateVar = Var[DtabState] with Updatable[DtabState]
  private[this] var dtabStates: Map[String, DtabStateVar] =
    namespaces.mapValues { dtab =>
      Var[DtabState](Some(VersionedDtab(dtab, InMemoryDtabStore.InitialVersion)))
      // mapValues produces a view.  We force it so that only one Var is created per-namespace.
    }.view.force

  private[this] def get(ns: String): DtabStateVar =
    dtabStatesMu.synchronized {
      dtabStates.get(ns) match {
        case Some(state) => state
        case None =>
          val state = Var[DtabState](None)
          dtabStates += (ns -> state)
          state
      }
    }

  private[this] lazy val keys = Var.async[Set[Ns]](Set.empty) { update =>
    @volatile var stopping = false

    def loop(): Future[Unit] = {
      if (stopping) Future.Unit
      else {
        dtabStatesMu.synchronized {
          val keySet = dtabStates.filter {
            case (key, value) => value.sample.isDefined
          }.keySet
          update.update(keySet)
        }
        Future.sleep(1.second)(DefaultTimer).before(loop())
      }
    }

    loop()

    Closable.make { _ =>
      stopping = true
      Future.Unit
    }
  }

  def list(): Activity[Set[Ns]] = Activity(keys.map(Activity.Ok(_)))

  def create(ns: String, dtab: Dtab): Future[Unit] = {
    val state = get(ns)
    dtabStatesMu.synchronized {
      state.sample match {
        case Some(_) =>
          Future.exception(new DtabNamespaceAlreadyExistsException(ns))
        case None =>
          state.update(Some(VersionedDtab(dtab, InMemoryDtabStore.InitialVersion)))
          Future.Unit
      }
    }
  }

  def delete(ns: String): Future[Unit] = {
    val state = get(ns)
    dtabStatesMu.synchronized {
      state.sample match {
        case Some(_) =>
          state.update(None)
          Future.Unit
        case None =>
          Future.exception(new DtabNamespaceDoesNotExistException(ns))
      }
    }
  }

  def update(ns: String, dtab: Dtab, version: Buf): Future[Unit] = {
    val state = get(ns)
    dtabStatesMu.synchronized {
      state.sample match {
        case Some(VersionedDtab(_, currentVersion)) if version == currentVersion =>
          state.update(Some(VersionedDtab(dtab, InMemoryDtabStore.nextVersion(version))))
          Future.Unit
        case Some(VersionedDtab(_, currentVersion)) =>
          Future.exception(new DtabVersionMismatchException)
        case None =>
          Future.exception(new DtabNamespaceDoesNotExistException(ns))
      }
    }
  }

  override def put(ns: String, dtab: Dtab): Future[Unit] = {
    val state = get(ns)
    dtabStatesMu.synchronized {
      val version = state.sample.map(_.version)
      val next = version.map(InMemoryDtabStore.nextVersion).getOrElse(InMemoryDtabStore.InitialVersion)
      state.update(Some(VersionedDtab(dtab, next)))
      Future.Unit
    }
  }

  def observe(ns: String): Activity[Option[VersionedDtab]] =
    Activity(get(ns).map(Activity.Ok(_)))
}

object InMemoryDtabStore {
  def InitialVersion: Buf = version(1)

  def version(n: Long): Buf = {
    val bb = ByteBuffer.allocate(8)
    bb.putLong(n)
    bb.rewind()
    Buf.ByteBuffer.Owned(bb)
  }

  def nextVersion(buf: Buf): Buf = {
    val bb = Buf.ByteBuffer.Owned.extract(buf)
    version(bb.getLong + 1)
  }
}
