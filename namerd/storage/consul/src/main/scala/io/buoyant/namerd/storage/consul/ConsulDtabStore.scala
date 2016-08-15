package io.buoyant.namerd
package storage.consul

import com.twitter.finagle.{Dtab, Failure, Path}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.consul.v1._
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException, Version}

class ConsulDtabStore(api: KvApi, root: Path) extends DtabStore {

  def path(ns: Ns) = s"${root.show}/$ns"

  def getNs(ns: Ns, index: Option[String] = None) =
    api.get(path(ns), blockingIndex = index, recurse = path(ns).endsWith("/"))

  def putNs(ns: Ns, value: String, cas: Option[String] = None) = api.put(path(ns), value, cas = cas)

  def watch[T](ns: Ns)(empty: T)(f: Indexed[Seq[Key]] => T) = {
    val run = Var.async[Activity.State[T]](Activity.Pending) { updates =>
      def cycle(index: Option[String]): Future[Unit] =
        getNs(ns, index = index).transform {
          case Return(result) =>
            updates() = Activity.Ok(f(result))
            cycle(result.index)
          case Throw(e: NotFound) =>
            updates() = Activity.Ok(empty)
            cycle(e.rsp.headerMap.get(Headers.Index))
          case Throw(e: Failure) if e.isFlagged(Failure.Interrupted) => Future.Unit
          case Throw(e) =>
            updates() = Activity.Failed(e)
            cycle(None)
        }
      val pending = cycle(None)

      Closable.make(_ => Future.value(pending.raise(new FutureCancelledException)))
    }

    Activity(run)
  }

  override def list(): Activity[Set[Ns]] = watch[Set[Ns]]("")(Set.empty[Ns]) { keys =>
    keys.value.map(_.Key.get.substring(root.show.length)).toSet // strip pathPrefix from keys
  }

  def create(ns: Ns, dtab: Dtab): Future[Unit] =
    putNs(ns, dtab.show, cas = Some("0")).flatMap { result =>
      if (result) Future.Done else Future.exception(new DtabNamespaceAlreadyExistsException(ns))
    }

  def delete(ns: Ns): Future[Unit] = getNs(ns).transform {
    case Return(_) => api.delete(path(ns)).unit
    case Throw(e: NotFound) => Future.exception(new DtabNamespaceDoesNotExistException(ns))
    case Throw(e) => Future.exception(e)
  }

  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = {
    val Buf.Utf8(vstr) = version
    Try(vstr.toLong) match {
      case Return(_) =>
        putNs(ns, dtab.show, cas = Some(vstr)).flatMap { result =>
          if (result) Future.Done else Future.exception(new DtabVersionMismatchException)
        }
      case _ => Future.exception(new DtabVersionMismatchException)
    }
  }

  def put(ns: Ns, dtab: Dtab): Future[Unit] = putNs(ns, dtab.show).unit

  def readDtabFromKey(key: Key): Dtab = key.decoded match {
    case Some(value) =>
      Try { Dtab.read(value) } match {
        case Return(dtab) => dtab
        case _ => Dtab.empty // key with wrong dtab syntax -> empty dtab
      }
    case _ => Dtab.empty // empty key -> empty dtab
  }

  def observe(ns: Ns): Activity[Option[VersionedDtab]] = watch[Option[VersionedDtab]](ns)(None) { keys =>
    // API returns list even when a single key requested - just handle output in a generic way
    val dtab = keys.value.map(readDtabFromKey).reduceLeft(_ ++ _)
    val version = Buf.Utf8(keys.index.get)
    Some(VersionedDtab(dtab, version))
  }
}
