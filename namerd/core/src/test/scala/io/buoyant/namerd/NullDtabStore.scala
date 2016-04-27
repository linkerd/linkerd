package io.buoyant.namerd

import com.twitter.finagle.Dtab
import com.twitter.util.{Activity, Future}
import io.buoyant.namerd.DtabStore.Version

object NullDtabStore extends DtabStore {
  override def list(): Activity[Set[Ns]] = Activity.pending
  override def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = Future.never
  override def put(ns: Ns, dtab: Dtab): Future[Unit] = Future.never
  override def observe(ns: Ns): Activity[Option[VersionedDtab]] = Activity.pending
  override def delete(ns: Ns): Future[Unit] = Future.never
  override def create(ns: Ns, dtab: Dtab): Future[Unit] = Future.never
}
