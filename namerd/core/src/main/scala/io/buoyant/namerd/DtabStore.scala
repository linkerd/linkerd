package io.buoyant.namerd

import com.twitter.finagle.Dtab
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}

case class VersionedDtab(dtab: Dtab, version: Buf)

trait DtabStore {

  /** List all namespaces */
  def list(): Future[Set[String]]

  /**
   * Create a new dtab.  Returns a DtabNamespaceAlreadyExistsException if a
   * dtab already exists with that namespace.
   */
  def create(ns: String, dtab: Dtab): Future[Unit]

  /**
   * Deletes a dtab.  Returns a DtabNamespaceDoesNotExistException if the
   * namespace does not exist.
   */
  def delete(ns: String): Future[Unit]

  /**
   * Update an existing dtab.  Returns a DtabVersionMismatchException if the
   * supplied version doesn't match the current version.
   */
  def update(ns: String, dtab: Dtab, version: Buf): Future[Unit]

  /**
   * Update an existing dtab regardless of the current version or create a new
   * dtab if one doesn't already exist.
   */
  def put(ns: String, dtab: Dtab): Future[Unit]

  /** Watch a dtab and it's version. */
  def observe(ns: String): Activity[Option[VersionedDtab]]
}

object DtabStore {
  class DtabNamespaceAlreadyExistsException(ns: String)
    extends Exception(s"The dtab namespace $ns already exists")
  class DtabVersionMismatchException
    extends Exception("Could not update dtab: current version does not match provided version")
  class DtabNamespaceDoesNotExistException(ns: String)
    extends Exception(s"The dtab namespace $ns does not exist")
}
