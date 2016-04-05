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

  /** Watch a dtab composed with its ancestors */
  def observeComposed(ns: String): Activity[Option[VersionedDtab]] = {
    def ancestors(ns: String): Seq[String] = {
      ns.split("/").reverse.tails.map(_.reverse.mkString("/")).toSeq.reverse.drop(1)
    }

    Activity.collect(ancestors(ns).map(observe).toList).map { dtabs =>
      dtabs.reduce[Option[VersionedDtab]] {
        case (Some(a), Some(b)) =>
          Some(VersionedDtab(a.dtab ++ b.dtab, b.version))
        case _ =>
          None
      }
    }
  }
}

object DtabStore {
  class DtabNamespaceAlreadyExistsException(ns: String)
    extends Exception(s"The dtab namespace $ns already exists")
  class DtabVersionMismatchException
    extends Exception("Could not update dtab: current version does not match provided version")
  class DtabNamespaceDoesNotExist(ns: String)
    extends Exception(s"The dtab namespace $ns does not exist")
}
