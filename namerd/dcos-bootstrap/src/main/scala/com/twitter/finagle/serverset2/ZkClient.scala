package com.twitter.finagle.serverset2

import com.twitter.finagle.Path
import com.twitter.finagle.serverset2.client.{ClientBuilder, CreateMode, Data, KeeperException, SessionState, WatchState, ZooKeeperWriter}
import com.twitter.io.Buf
import com.twitter.util.{Closable, Duration, Future, Time}

/**
 * A Zk Client for recursively creating paths
 */
class ZkClient(
  hosts: String,
  zkPrefix: String
) extends Closable {
  private[this] val builder = ClientBuilder()
    .hosts(hosts)
    .sessionTimeout(ZkSession.DefaultSessionTimeout)
  private[this] val watchedZkw = builder.writer()
  private[this] val zkw = watchedZkw.value

  def create(ns: String, data: Buf): Future[Unit] =
    watchedZkw.state.changes.filter {
      // wait until we are connected
      _ == WatchState.SessionState(SessionState.SyncConnected)
      // TODO: send auth
    }.toFuture.flatMap { _ =>
      ensurePath(zkPrefix)
    }.flatMap { _ =>
      zkw.create(
        s"$zkPrefix/$ns",
        Some(data),
        Seq(Data.ACL.AnyoneAllUnsafe),
        CreateMode.Persistent
      ).handle { case KeeperException.NodeExists(_) => () }
    }.unit

  private[this] def ensurePath(path: String): Future[Unit] = {
    val components = Path.read(path)
    if (components.size == 0) {
      return Future.Unit
    }

    ensurePath(components.take(components.size - 1).show).flatMap { _ =>
      zkw.create(
        path,
        None,
        Seq(Data.ACL.AnyoneAllUnsafe),
        CreateMode.Persistent
      ).unit.handle { case KeeperException.NodeExists(_) => () }
    }
  }

  def close(deadline: Time) = zkw.close(deadline)
}
