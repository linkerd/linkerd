package com.twitter.finagle.serverset2

import com.twitter.finagle.Path
import com.twitter.finagle.serverset2.client.{ClientBuilder, CreateMode, Data, KeeperException, SessionState, WatchState, ZooKeeperWriter}
import com.twitter.io.Buf
import com.twitter.util.{Duration, Future}

/**
 * A generic Zk Client
 */
class ZkClient(
  hosts: String,
  zkPrefix: String,
  sessionTimeout: Option[Duration]
) {
  private[this] val builder = ClientBuilder()
    .hosts(hosts)
    .sessionTimeout(sessionTimeout.getOrElse(ZkSession.DefaultSessionTimeout))

  def create(ns: String, data: Buf): Future[Unit] = {
    writerConnect().flatMap { zkw =>
      ensurePath(zkw, zkPrefix).flatMap { _ =>
        zkw.create(
          s"$zkPrefix/$ns",
          Some(data),
          Seq(Data.ACL.AnyoneAllUnsafe),
          CreateMode.Persistent
        ).rescue {
            case KeeperException.NodeExists(_) => Future.Unit
          }
      }
    }.unit
  }

  private[this] def ensurePath(zkw: ZooKeeperWriter, path: String): Future[Unit] = {
    val components = Path.read(path)
    if (components.size == 0) {
      return Future.Unit
    }

    ensurePath(zkw, components.take(components.size - 1).show).flatMap { _ =>
      zkw.create(
        path,
        None,
        Seq(Data.ACL.AnyoneAllUnsafe),
        CreateMode.Persistent
      ).unit.handle { case KeeperException.NodeExists(_) => }
    }
  }

  // TODO: share with ZkDtabStore
  private[this] def writerConnect(): Future[ZooKeeperWriter] = {
    val watchedZkw = builder.writer()
    val zkw = watchedZkw.value

    watchedZkw.state.changes.filter {
      // wait until we are connected
      _ == WatchState.SessionState(SessionState.SyncConnected)
      // TODO: send auth
    }.toFuture.map(_ => zkw)
  }
}
