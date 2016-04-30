package io.buoyant.namerd
package storage.etcd

import com.twitter.finagle.Dtab
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}
import io.buoyant.etcd.Key

class EtcdDtabStore(key: Key) extends DtabStore {
  import DtabStore.Version

  def list(): Activity[Set[Ns]] = ???

  def create(ns: Ns, dtab: Dtab): Future[Unit] = ???

  def delete(ns: Ns): Future[Unit] = ???

  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = ???

  def put(ns: Ns, dtab: Dtab): Future[Unit] = ???

  def observe(ns: Ns): Activity[Option[VersionedDtab]] = ???
}
