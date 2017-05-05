package com.twitter.finagle.serverset2.buoyant

import com.twitter.finagle.Dtab
import com.twitter.finagle.serverset2.client._
import com.twitter.finagle.serverset2.{RetryStream, Zk2Resolver, ZkSession => FZkSession}
import com.twitter.finagle.stats.DefaultStatsReceiver
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException, Forbidden}
import io.buoyant.namerd.storage.{Acl, AuthInfo}
import io.buoyant.namerd.{DtabStore, Ns, VersionedDtab}
import java.nio.ByteBuffer

/**
 * A DtabStore which stores dtabs in ZooKeeper.  Dtabs are stored, Utf8
 * encoded, in the ZooKeeper node /dtabs/<ns>.
 */
class ZkDtabStore(
  hosts: String,
  zkPrefix: String,
  sessionTimeout: Option[Duration],
  authInfo: Option[AuthInfo],
  acls: Seq[Acl]
) extends DtabStore {

  private[this] val log = Logger()
  private[this] val retryStream = RetryStream()
  private[this] val stats = DefaultStatsReceiver.scope("zkclient").scope(Zk2Resolver.statsOf(hosts))

  private[this] implicit val timer = DefaultTimer
  private[this] val builder = ClientBuilder()
    .hosts(hosts)
    .sessionTimeout(sessionTimeout.getOrElse(FZkSession.DefaultSessionTimeout))
    .statsReceiver(stats)

  private[this] val zkSession = new ZkSession(
    retryStream,
    retryStream,
    () => builder.writer(),
    authInfo,
    stats
  )
  private[this] def actOf[T](go: ZooKeeperRW => Future[Watched[T]]): Activity[T] =
    zkSession.watchedOperation(go(zkSession.zk))

  private[this] val actNs = actOf(_.getChildrenWatch(zkPrefix)).map(_.children.toSet)

  def list(): Activity[Set[Ns]] = actNs.transform {
    case Activity.Failed(KeeperException.NoAuth(_)) => Activity.exception(Forbidden)
    case state => Activity(Var(state))
  }

  def create(ns: String, dtab: Dtab): Future[Unit] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to create dtab at $path")

    zkSession.zk.create(
      path,
      Some(Buf.Utf8(dtab.show)),
      acls.map(ZkDtabStore.zkAcl),
      CreateMode.Persistent
    ).rescue {
        case KeeperException.NodeExists(_) =>
          Future.exception(new DtabNamespaceAlreadyExistsException(ns))
        case KeeperException.NoAuth(_) =>
          Future.exception(Forbidden)
      }.unit
  }

  def delete(ns: String): Future[Unit] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to delete dtab at $path")

    zkSession.zk.delete(path, None).rescue {
      case KeeperException.NoNode(_) =>
        Future.exception(new DtabNamespaceDoesNotExistException(ns))
      case KeeperException.NoAuth(_) =>
        Future.exception(Forbidden)
    }
  }

  override def update(ns: String, dtab: Dtab, version: Buf): Future[Unit] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to update dtab at $path")

    zkSession.zk.setData(
      path,
      Some(Buf.Utf8(dtab.show)),
      Some(versionInt(version))
    ).rescue {
        case KeeperException.BadVersion(_) => Future.exception(new DtabVersionMismatchException)
        case KeeperException.NoNode(_) => Future.exception(new DtabNamespaceDoesNotExistException(ns))
        case KeeperException.NoAuth(_) => Future.exception(Forbidden)
      }.unit
  }

  def observe(ns: String): Activity[Option[VersionedDtab]] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to observe $path")

    actOf(_.getDataWatch(path)).flatMap { data =>
      data.data match {
        case Some(Buf.Utf8(s)) =>
          Activity.value(Some(VersionedDtab(Dtab.read(s), versionBuf(data.stat.version))))
        case None =>
          Activity.exception(new IllegalStateException(s"Empty node: $path"))
      }
    }.transform {
      case Activity.Failed(KeeperException.NoNode(_)) => Activity.value(None)
      case Activity.Failed(KeeperException.NoAuth(_)) => Activity.exception(Forbidden)
      case state => Activity(Var.value(state))
    }
  }

  override def put(ns: String, dtab: Dtab): Future[Unit] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to put dtab at $path")

    // Send a create and a setData at the same time in hopes that one of them will succeed.
    // If both fail, the user can always retry the put.
    Future.collectToTry(
      Seq(
        zkSession.zk.create(
        path,
        Some(Buf.Utf8(dtab.show)),
        acls.map(ZkDtabStore.zkAcl),
        CreateMode.Persistent
      ).unit,
        zkSession.zk.setData(path, Some(Buf.Utf8(dtab.show)), None).unit
      )
    ).flatMap {
        case Seq(Throw(KeeperException.NoAuth(_)), _) |
          Seq(_, Throw(KeeperException.NoAuth(_))) => Future.exception(Forbidden)
        case Seq(Throw(e1), Throw(e2)) => Future.exception(new Exception("Failed to put, try again."))
        case Seq(_, _) => Future.Done
      }
  }

  private[this] def versionInt(version: Buf): Int = {
    val bb = Buf.ByteBuffer.Owned.extract(version)
    bb.getInt
  }

  private[this] def versionBuf(version: Int): Buf = {
    val bb = ByteBuffer.allocate(4)
    bb.putInt(version)
    bb.rewind()
    Buf.ByteBuffer.Owned(bb)
  }
}

object ZkDtabStore {
  def zkAcl(acl: Acl): Data.ACL = {
    val perms = acl.perms.map {
      case 'r' => Perms.Read
      case 'w' => Perms.Write
      case 'c' => Perms.Create
      case 'd' => Perms.Delete
      case 'a' => Perms.Admin
      case c => throw new IllegalArgumentException(s"$c is not a valid permission")
    }.sum
    Data.ACL(perms, Data.Id(acl.scheme, acl.id))
  }
}
