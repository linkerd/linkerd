package io.buoyant.namerd

import com.twitter.finagle.Dtab
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}

case class VersionedDtab(dtab: Dtab, version: DtabStore.Version)

trait DtabStore {
  import DtabStore.Version

  /** List all namespaces */
  def list(): Activity[Set[Ns]]

  /**
   * Create a new dtab.  Returns a DtabNamespaceAlreadyExistsException if a
   * dtab already exists with that namespace.
   */
  def create(ns: Ns, dtab: Dtab): Future[Unit]

  /**
   * Deletes a dtab.  Returns a DtabNamespaceDoesNotExistException if the
   * namespace does not exist.
   */
  def delete(ns: Ns): Future[Unit]

  /**
   * Update an existing dtab.  Returns a DtabVersionMismatchException if the
   * supplied version doesn't match the current version.
   */
  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit]

  /**
   * Update an existing dtab regardless of the current version or create a new
   * dtab if one doesn't already exist.
   */
  def put(ns: Ns, dtab: Dtab): Future[Unit]

  /** Watch a dtab and its version. */
  def observe(ns: Ns): Activity[Option[VersionedDtab]]
}

object DtabStore {
  type Version = Buf

  class DtabNamespaceAlreadyExistsException(ns: Ns)
    extends Exception(s"The dtab namespace $ns already exists")

  class DtabVersionMismatchException
    extends Exception("Could not update dtab: current version does not match provided version")

  class DtabNamespaceDoesNotExistException(ns: Ns)
    extends Exception(s"The dtab namespace $ns does not exist")

  object Forbidden extends Exception("You do not have sufficient permissions")

  class Proxy(underlying: DtabStore) extends DtabStore {
    protected[this] val self = underlying

    def list(): Activity[Set[Ns]] = self.list()
    def create(ns: Ns, dtab: Dtab): Future[Unit] = self.create(ns, dtab)
    def delete(ns: Ns): Future[Unit] = self.delete(ns)
    def update(ns: Ns, dtab: Dtab, version: Buf): Future[Unit] = self.update(ns, dtab, version)
    def put(ns: Ns, dtab: Dtab): Future[Unit] = self.put(ns, dtab)
    def observe(ns: Ns): Activity[Option[VersionedDtab]] = self.observe(ns)
  }

  abstract class Validator(underlying: DtabStore) extends Proxy(underlying) {
    /** Fails if the provided dtab cannot be validated. */
    protected[this] def validate(ns: Ns, dtab: Dtab): Future[Unit]

    override def create(ns: Ns, dtab: Dtab): Future[Unit] =
      validate(ns, dtab) before self.create(ns, dtab)

    override def update(ns: Ns, dtab: Dtab, version: Buf): Future[Unit] =
      validate(ns, dtab) before self.update(ns, dtab, version)

    override def put(ns: Ns, dtab: Dtab): Future[Unit] =
      validate(ns, dtab) before self.put(ns, dtab)
  }

}
