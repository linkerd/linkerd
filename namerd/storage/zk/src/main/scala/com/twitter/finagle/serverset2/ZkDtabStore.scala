package com.twitter.finagle.serverset2

import com.twitter.finagle.Dtab
import com.twitter.finagle.serverset2.client.KeeperException.{BadVersion, NoNode}
import com.twitter.finagle.serverset2.client._
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.namerd.{VersionedDtab, DtabStore, RichActivity}
import io.buoyant.namerd.DtabStore.{DtabNamespaceDoesNotExistException, DtabVersionMismatchException, DtabNamespaceAlreadyExistsException}
import java.nio.ByteBuffer

/**
 * A DtabStore which stores dtabs in ZooKeeper.  Dtabs are stored, Utf8
 * encoded, in the ZooKeeper node /dtabs/<ns>.
 */
class ZkDtabStore(
  hosts: String,
  zkPrefix: String,
  sessionTimeout: Option[Duration]
) extends DtabStore {

  private[this] val log = Logger()
  private[this] val retryStream = RetryStream()
  private[this] val stats = DefaultStatsReceiver.scope("zkclient").scope(Zk2Resolver.statsOf(hosts))

  private[this] implicit val timer = DefaultTimer.twitter
  private[this] val builder = ClientBuilder()
    .hosts(hosts)
    .sessionTimeout(sessionTimeout.getOrElse(ZkSession.DefaultSessionTimeout))
    .statsReceiver(stats)
  private[this] val readerBuilder = builder.readOnlyOK()

  private[this] val varZk = ZkSession.retrying(
    retryStream,
    () => new EnhancedZkSession(retryStream, readerBuilder.reader(), stats)
  ).asInstanceOf[Var[EnhancedZkSession]]
  private[this] val actZk = Activity(varZk.map(Activity.Ok(_)))

  private[this] val actNs = for {
    zk <- actZk
    children <- zk.getChildrenOf(zkPrefix)
  } yield children.children.toSet

  def list(): Future[Set[String]] = actNs.toFuture

  def create(ns: String, dtab: Dtab): Future[Unit] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to create dtab at $path")

    writerConnect().flatMap { zkw =>
      zkw.create(
        path,
        Some(Buf.Utf8(dtab.show)),
        Seq(Data.ACL.AnyoneAllUnsafe),
        CreateMode.Persistent
      ).rescue {
          case KeeperException.NodeExists(_) =>
            Future.exception(new DtabNamespaceAlreadyExistsException(ns))
        }
    }.unit
  }

  def delete(ns: String): Future[Unit] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to delete dtab at $path")
    writerConnect().flatMap { zkw =>
      zkw.delete(path, None)
    }.rescue {
      case KeeperException.NoNode(_) =>
        Future.exception(new DtabNamespaceDoesNotExistException(ns))
    }
  }

  override def update(ns: String, dtab: Dtab, version: Buf): Future[Unit] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to update dtab at $path")

    writerConnect().flatMap { zkw =>
      zkw.setData(path, Some(Buf.Utf8(dtab.show)), Some(versionInt(version))).rescue {
        case BadVersion(_) => Future.exception(new DtabVersionMismatchException)
        case NoNode(_) => Future.exception(new DtabNamespaceDoesNotExistException(ns))
      }
    }.unit
  }

  private[this] val dtabWatchOp = Memoize { path: String =>
    log.info(s"Attempting to observe $path")
    actZk.flatMap(_.getDataOf(path)).flatMap { data =>
      data.data match {
        case Some(Buf.Utf8(s)) => Activity.value(Some(VersionedDtab(Dtab.read(s), versionBuf(data.stat.version))))
        case None => Activity.exception(new IllegalStateException(s"Empty node: $path"))
      }
    }.handle {
      case NoNode(_) => None
    }
  }

  def observe(ns: String): Activity[Option[VersionedDtab]] = dtabWatchOp(s"$zkPrefix/$ns")

  override def put(ns: String, dtab: Dtab): Future[Unit] = {
    val path = s"$zkPrefix/$ns"
    log.info(s"Attempting to put dtab at $path")

    writerConnect().flatMap { zkw =>
      // Send a create and a setData at the same time in hopes that one of them will succeed.
      // If both fail, the user can always retry the put.
      Future.collectToTry(Seq(
        zkw.create(
        path,
        Some(Buf.Utf8(dtab.show)),
        Seq(Data.ACL.AnyoneAllUnsafe),
        CreateMode.Persistent
      ).unit,
        zkw.setData(path, Some(Buf.Utf8(dtab.show)), None).unit
      ))
    }.flatMap {
      case Seq(Throw(e1), Throw(e2)) => Future.exception(new Exception("Failed to put, try again."))
      case Seq(_, _) => Future.Done
    }
  }

  private[this] def writerConnect(): Future[ZooKeeperWriter] = {
    val watchedZkw = builder.writer()
    val zkw = watchedZkw.value

    watchedZkw.state.changes.filter {
      // wait until we are connected
      _ == WatchState.SessionState(SessionState.SyncConnected)
      // TODO: send auth
    }.toFuture.map(_ => zkw)
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

/**
 * A ZkSession that also exposes a watch on node data.
 */
class EnhancedZkSession(
  retryStream: RetryStream,
  watchedZk: Watched[ZooKeeperReader],
  stats: StatsReceiver
)(implicit timer: Timer) extends ZkSession(retryStream, watchedZk, stats) {

  private[this] val zkr = watchedZk.value

  private[this] val getDataWatchOp = Memoize { path: String =>
    watchedOperation(zkr.getDataWatch(path))
  }

  private[this] val getChildrenWatchOp = Memoize { path: String =>
    watchedOperation(zkr.getChildrenWatch(path))
  }

  def getDataOf(path: String): Activity[Node.Data] = getDataWatchOp(path)

  def getChildren(path: String): Future[Node.Children] = zkr.getChildren(path)

  def getChildrenOf(path: String): Activity[Node.Children] = getChildrenWatchOp(path)
}
