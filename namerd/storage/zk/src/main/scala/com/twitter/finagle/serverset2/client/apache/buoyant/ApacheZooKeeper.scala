// Modified from com.twitter.finagle.serverset2.client.apache.ApacheZooKeeper | (c) 2019 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
package com.twitter.finagle.serverset2.client.apache.buoyant

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.serverset2.client._
import com.twitter.finagle.serverset2.client.apache._
import com.twitter.finagle.serverset2.client.apache.{ApacheZooKeeper => ApacheZooKeeperOld}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import org.apache.zookeeper
import org.apache.zookeeper.AsyncCallback._
import org.apache.zookeeper.Watcher.WatcherType

import scala.jdk.CollectionConverters._

/**
 * ZooKeeperClient implementation based on Apache ZooKeeper Library
 *
 * Provides Reader and Writer.
 * No Multi support.
 *
 * @param zk Underlying Apache ZooKeeper client.
 */
private[serverset2] class ApacheZooKeeper private[serverset2] (zk: zookeeper.ZooKeeper)
  extends ApacheZooKeeperOld(zk) {

  private def fromZKData(data: Array[Byte]): Option[Buf] = data match {
    case null => None
    case x => Some(Buf.ByteArray.Owned(x))
  }

  override def getDataWatch(path: String): Future[Watched[Node.Data]] = {
    val watcher = new ApacheWatcher
    val rv = new Promise[Watched[Node.Data]]
    rv.setInterruptHandler {
      case ex: FutureCancelledException =>
        rv.setException(ex)
        zk.removeWatches(path, watcher, WatcherType.Data, true)
    }
    val cb = new DataCallback {
      def processResult(
        ret: Int,
        path: String,
        ctx: Object,
        data: Array[Byte],
        stat: zookeeper.data.Stat
      ) =
        ApacheKeeperException(ret, Option(path)) match {
          case None =>
            rv.setValue(Watched(Node.Data(fromZKData(data), ApacheData.Stat(stat)), watcher.state))
          case Some(e) => rv.setException(e)
        }
    }
    try {
      zk.getData(path, watcher, cb, null)
    } catch {
      case t: Throwable =>
        rv.setException(t)
    }
    rv
  }
}

private[serverset2] object ApacheZooKeeper {

  /**
   * Create a new ZooKeeper client from a ClientConfig.
   *
   * @param config
   * @return a Watched[ZooKeeperRW]
   */
  private[serverset2] def newClient(config: ClientConfig): Watched[ZooKeeperRW] = {
    val timeoutInMs = config.sessionTimeout.inMilliseconds.toInt
    val statsReceiver = config.statsReceiver
    val watcher = new ApacheWatcher(statsReceiver)
    val statsWatcher = SessionStats.watcher(
      watcher.state,
      statsReceiver,
      5.seconds,
      config.timer
    )
    val zk = (config.sessionId, config.password) match {
      case (Some(id), Some(pw)) =>
        new ApacheZooKeeper(
          new zookeeper.ZooKeeper(config.hosts, timeoutInMs, watcher, id, toByteArray(pw))
        )
      case _ => new ApacheZooKeeper(new zookeeper.ZooKeeper(config.hosts, timeoutInMs, watcher))
    }
    val wrappedZk: ZooKeeperRW = new StatsRW {
      protected val underlying: ZooKeeperRW = zk
      protected val stats: StatsReceiver = statsReceiver
    }
    if (com.twitter.finagle.serverset2.client.chatty()) {
      val logger = Logger.get(getClass)
      Watched(new ChattyRW {
        protected val underlying: ZooKeeperRW = wrappedZk
        protected val print = { m: String =>
          logger.info(m)
        }
      }, statsWatcher)
    } else
      Watched(wrappedZk, statsWatcher)
  }
}
