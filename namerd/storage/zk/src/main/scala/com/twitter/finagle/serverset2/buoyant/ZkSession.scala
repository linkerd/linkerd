// modified from com.twitter.finagle.serverset2.ZkSession | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
package com.twitter.finagle.serverset2.buoyant

import com.twitter.concurrent.AsyncSemaphore
import com.twitter.finagle.serverset2.RetryStream
import com.twitter.finagle.serverset2.client._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.namerd.storage.experimental.AuthInfo

/**
 * ZkSession wraps a ZooKeeper client which automatically reconnects upon session expiry.
 * Cribbed from https://github.com/twitter/finagle/blob/develop/finagle-serversets/src/main/scala/com/twitter/finagle/serverset2/ZkSession.scala
 */
class ZkSession(
  retryBackoff: RetryStream,
  reconnectBackoff: RetryStream,
  clientBuilder: () => Watched[ZooKeeperRW],
  authInfo: Option[AuthInfo],
  statsReceiver: StatsReceiver
)(implicit timer: Timer) {
  val logger = Logger()

  private[this] val client =
    Var(Watched[ZooKeeperRW](NullZooKeeperRW, Var(WatchState.Pending)))
  def zk = client.sample.value
  private[this] def state = client.sample.state

  reconnect()

  @volatile private[this] var closing = false

  def close(): Unit = {
    closing = true
    zk.close()
  }

  private[this] def reconnect(): Unit = {
    if (closing) return

    logger.info(s"Closing zk session $sessionId")
    zk.close()
    val newClient = clientBuilder()
    logger.info(s"Starting new zk session ${sessionId(newClient)}")

    // Upon initial connection, send auth info, then update `client`
    newClient.state.changes.filter {
      _ == WatchState.SessionState(SessionState.SyncConnected)
    }.toFuture.unit.before {
      authInfo match {
        case Some(AuthInfo(scheme, auth)) => newClient.value.addAuthInfo(scheme, Buf.Utf8(auth))
        case None => Future.Unit
      }
    }.onSuccess { _ =>
      logger.info(s"New ZKSession is connected. Session ID: ${sessionId(newClient)}")
      client() = newClient
      reconnectBackoff.reset()
    }

    // Kick off a delayed reconnection on session expiration.
    newClient.state.changes.filter {
      _ == WatchState.SessionState(SessionState.Expired)
    }.toFuture().unit.before {
      val jitter = reconnectBackoff.next()
      logger.error(s"Zookeeper session ${sessionId(newClient)} has expired. Reconnecting in $jitter")
      Future.sleep(jitter)
    }.ensure { reconnect() }
  }

  // If the zookeeper cluster is under duress, there can be 100's of thousands of clients
  // attempting to read and write at once. Limit to a (fairly large) concurrent request cap.
  // Use a semaphore (versus explicit rate limiting) to approximate the throughput of the cluster.
  // N.B. this semaphore has no max-waiters limit. This could lead to an OOME if the zk operations
  // never complete. This is preferable to handling and re-queuing (via future.sleep etc)
  // the error if an arbitrary max-limit is set.
  private[this] val limiter = new AsyncSemaphore(100)
  private[this] val waitersGauge = statsReceiver.addGauge("numWaiters") { limiter.numWaiters }

  private[this] def limit[T](f: => Future[T]): Future[T] =
    limiter.acquire().flatMap { permit =>
      f.ensure {
        // don't release the permit until f is complete
        permit.release()
      }
    }

  private[this] def retryWithDelay[T](f: => Future[T]): Future[T] =
    Future.sleep(retryBackoff.next()).before(f)

  /**
   * Invoke a `Future[T]`-producing operation, retrying on
   * [[com.twitter.finagle.serverset2.client.KeeperException.ConnectionLoss]]
   * according to a backoff schedule defined by [[retryBackoff]]. The operation itself
   * will be limited by the session-level semaphore.
   */
  private def safeRetry[T](go: => Future[T]): Future[T] = {
    def loop(): Future[T] =
      limit { go }.rescue {
        case exc: KeeperException.ConnectionLoss =>
          logger.warning(s"ConnectionLoss to Zookeeper host. Session $sessionId. Retrying")
          retryWithDelay { loop() }
      }

    loop()
  }

  /**
   * A persistent operation: reissue a watched operation every
   * time the watch fires, applying safe retries when possible.
   *
   * The returned Activity is asynchronous: watches aren't reissued
   * when the Activity is no longer observed.
   */
  def watchedOperation[T](go: => Future[Watched[T]]): Activity[T] =
    Activity(Var.async[Activity.State[T]](Activity.Pending) { u =>
      @volatile var closed = false

      def loop(): Future[Unit] = {
        if (!closed) safeRetry(go).respond {
          case Throw(e@KeeperException.SessionExpired(_)) =>
            // don't retry. The session has expired while trying to set the watch.
            // In case our activity is still active, notify the listener
            u() = Activity.Failed(e)

          case Throw(exc) =>
            logger.error(s"Operation failed with $exc. Session $sessionId")
            u() = Activity.Failed(exc)
            retryWithDelay { loop() }

          case Return(Watched(value, state)) =>
            val ok = Activity.Ok(value)
            retryBackoff.reset()
            u() = ok

            state.changes.respond {
              case WatchState.Pending =>
              // Ignore updates WatchState is Pending.

              case WatchState.Determined(_) =>
                // Note: since the watch transitioned to determined, we know
                // that this observation will produce no more values, so there's
                // no need to apply concurrency control to the subsequent
                // branches.
                loop()

              case WatchState.SessionState(sessionState) if sessionState == SessionState.ConnectedReadOnly |
                sessionState == SessionState.SaslAuthenticated |
                sessionState == SessionState.SyncConnected =>
                u() = ok
                logger.info(s"Reacquiring watch on $sessionState. Session: $sessionId")
                // We may have lost or never set our watch correctly. Retry to ensure we stay connected
                retryWithDelay { loop() }

              case WatchState.SessionState(SessionState.Expired) =>
                u() = Activity.Failed(new Exception("session expired"))
              // Do NOT retry here as the session has expired. We expect the watcher of this
              // ZkSession to retry at this point (See [[ZkSession.retrying]]).

              // Disconnected, NoSyncConnected
              case WatchState.SessionState(sessionState) if sessionState == SessionState.Disconnected |
                sessionState == SessionState.NoSyncConnected =>
                logger.warning(s"Intermediate Failure session state: $sessionState. " +
                  s"Session: $sessionId. Data is now unavailable.")
                u() = Activity.Failed(new Exception("" + sessionState))
              // Do NOT keep retrying, wait to be reconnected automatically by the underlying session

              case WatchState.SessionState(sessionState) =>
                logger.error(s"Unexpected session state $sessionState. Session: $sessionId")
                u() = Activity.Failed(new Exception("" + sessionState))
                // We don't know what happened. Retry.
                retryWithDelay { loop() }
            }
        }
        Future.Done
      }

      loop()

      Closable.make { deadline =>
        closed = true
        Future.Done
      }
    })

  private[this] def sessionId: String = sessionId(client.sample)
  private[this] def sessionId(wzk: Watched[ZooKeeperRW]): String =
    wzk.value.sessionId.toHexString

}
