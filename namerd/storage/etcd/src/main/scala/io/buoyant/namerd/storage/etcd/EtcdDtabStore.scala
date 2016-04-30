package io.buoyant.namerd
package storage.etcd

import com.twitter.finagle.{Dtab, Path}
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}
import io.buoyant.etcd.Key

class EtcdDtabStore(root: Key) extends DtabStore {
  import DtabStore.Version

  def list(): Activity[Set[Ns]] = ???

  def create(ns: Ns, dtab: Dtab): Future[Unit] = ???

  def delete(ns: Ns): Future[Unit] = ???

  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = {
    val Buf.Utf8(vstr) = version
    val index = vstr.toLong
    root.key(Path.Utf8(ns)).compareAndSwap(Buf.Utf8(dtab.show), prevIndex = Some(index)).unit
  }

  def put(ns: Ns, dtab: Dtab): Future[Unit] =
    root.key(Path.Utf8(ns)).set(Some(Buf.Utf8(dtab.show))).unit

  def observe(ns: Ns): Activity[Option[VersionedDtab]] = ???
}
