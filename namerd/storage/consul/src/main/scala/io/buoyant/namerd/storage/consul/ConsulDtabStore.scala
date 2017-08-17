package io.buoyant.namerd
package storage.consul

import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.twitter.finagle.{Dtab, Failure, Path}
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.consul.v1._
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException, Version}

class ConsulDtabStore(
  api: KvApi,
  root: Path,
  datacenter: Option[String] = None,
  readConsistency: Option[ConsistencyMode] = None,
  writeConsistency: Option[ConsistencyMode] = None
) extends DtabStore {

  private[this] val log = Logger.get("consul")

  override val list: Activity[Set[Ns]] = {
    def namespace(key: String): Ns = key.stripPrefix("/").stripSuffix("/").substring(root.show.length)

    val run = Var.async[Activity.State[Set[Ns]]](Activity.Pending) { updates =>
      @volatile var running = true

      def cycle(index: Option[String]): Future[Unit] =
        if (running)
          api.list(
            s"${root.show}/",
            blockingIndex = index,
            datacenter = datacenter,
            consistency = readConsistency,
            retry = true
          )
            .transform {
              case Return(result) =>
                val namespaces = result.value.map(namespace).toSet
                updates() = Activity.Ok(namespaces)
                cycle(result.index)
              case Throw(e: NotFound) =>
                updates() = Activity.Ok(Set.empty[Ns])
                cycle(e.rsp.headerMap.get(Headers.Index))
              case Throw(e: Failure) if e.isFlagged(Failure.Interrupted) => Future.Done
              case Throw(e) =>
                updates() = Activity.Failed(e)
                log.error("consul ns list observation error %s", e)
                cycle(None)
            }
        else
          Future.Unit
      val pending = cycle(None)

      Closable.make { _ =>
        running = false
        pending.raise(Failure("Consul observation released", Failure.Interrupted))
        Future.Unit
      }
    }

    Activity(run).stabilize
  }

  def create(ns: Ns, dtab: Dtab): Future[Unit] =
    api.put(
      s"${root.show}/$ns",
      dtab.show,
      cas = Some("0"),
      datacenter = datacenter,
      consistency = writeConsistency
    ).flatMap { result =>
        if (result) Future.Done else Future.exception(new DtabNamespaceAlreadyExistsException(ns))
      }

  def delete(ns: Ns): Future[Unit] = {
    val key = s"${root.show}/$ns"
    api.get(key, datacenter = datacenter, consistency = writeConsistency).transform {
      case Return(_) => api.delete(
        key,
        datacenter = datacenter,
        consistency = writeConsistency
      ).unit
      case Throw(e: NotFound) => Future.exception(new DtabNamespaceDoesNotExistException(ns))
      case Throw(e) => Future.exception(e)
    }
  }

  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = {
    val Buf.Utf8(vstr) = version
    Try(vstr.toLong) match {
      case Return(_) =>
        api.put(
          s"${root.show}/$ns",
          dtab.show,
          cas = Some(vstr),
          datacenter = datacenter,
          consistency = writeConsistency
        ).flatMap { result =>
            if (result) Future.Done else Future.exception(new DtabVersionMismatchException)
          }
      case _ => Future.exception(new DtabVersionMismatchException)
    }
  }

  def put(ns: Ns, dtab: Dtab): Future[Unit] =
    api.put(
      s"${root.show}/$ns",
      dtab.show,
      datacenter = datacenter,
      consistency = writeConsistency
    ).unit

  // We don't hold cached observations open so caching these is very cheap.  Therefore we don't
  // limit the size of this cache.
  private[this] val dtabCache = CacheBuilder.newBuilder()
    .build[Ns, Activity[Option[VersionedDtab]]](
      new CacheLoader[Ns, Activity[Option[VersionedDtab]]] {
        override def load(key: Ns): Activity[Option[VersionedDtab]] = _observe(key)
      }
    )

  def observe(ns: Ns): Activity[Option[VersionedDtab]] = dtabCache.get(ns).stabilize

  private[this] def _observe(ns: Ns): Activity[Option[VersionedDtab]] = {
    val key = s"${root.show}/$ns"
    val run = Var.async[Activity.State[Option[VersionedDtab]]](Activity.Pending) { updates =>
      @volatile var running = true

      def cycle(index: Option[String]): Future[Unit] =
        if (running)
          api.get(
            key,
            blockingIndex = index,
            datacenter = datacenter,
            retry = true,
            consistency = readConsistency
          ).transform {
            case Return(result) =>
              val version = Buf.Utf8(result.index.get)
              val dtab = Dtab.read(result.value)
              updates() = Activity.Ok(Some(VersionedDtab(dtab, version)))
              cycle(result.index)
            case Throw(e: NotFound) =>
              updates() = Activity.Ok(None)
              cycle(e.rsp.headerMap.get(Headers.Index))
            case Throw(e: Failure) if e.isFlagged(Failure.Interrupted) => Future.Done
            case Throw(e) =>
              updates() = Activity.Failed(e)
              log.error("consul ns %s dtab observation error %s", ns, e)
              cycle(None)
          }
        else
          Future.Unit
      val pending = cycle(None)

      Closable.make { _ =>
        running = false
        pending.raise(Failure("Consul observation released", Failure.Interrupted))
        Future.Unit
      }
    }
    Activity(run).stabilize
  }
}
