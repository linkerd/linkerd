// modified from com.twitter.finagle.serverset2.ZkSession | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
package com.twitter.finagle.serverset2.client.buoyant

import com.twitter.concurrent.AsyncSemaphore
import com.twitter.finagle.serverset2.RetryStream
import com.twitter.finagle.serverset2.client.WatchState.Determined
import com.twitter.finagle.serverset2.client._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.namerd.storage.AuthInfo

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
  def zk: Var[ZooKeeperRW] = client.map(_.value)
  private[this] def state = client.sample.state

  reconnect()

  @volatile private[this] var closing = false

  def close(): Future[Unit] = {
    closing = true
    zk.sample.close()
  }

  private[this] def reconnect(): Unit = {
    if (closing) return

    logger.info("Closing zk session %s", sessionId)
    zk.sample.close()
    val newClient = clientBuilder()
    logger.info("Starting new zk session %s", sessionId(newClient))

    // Upon initial connection, send auth info, then update `client`
    newClient.state.changes.filter {
      _ == WatchState.SessionState(SessionState.SyncConnected)
    }.toFuture.unit.before {
      authInfo match {
        case Some(AuthInfo(scheme, auth)) => newClient.value.addAuthInfo(scheme, Buf.Utf8(auth))
        case None => Future.Unit
      }
    }.onSuccess { _ =>
      logger.info("New ZKSession is connected. Session ID: %s", sessionId(newClient))
      client() = newClient
      reconnectBackoff.reset()
    }

    // Kick off a delayed reconnection on session expiration.
    fireAndForget {
      newClient.state.changes.filter {
        _ == WatchState.SessionState(SessionState.Expired)
      }.toFuture().unit.before {
        val jitter = reconnectBackoff.next()
        logger
          .error("Zookeeper session %s has expired. Reconnecting in %s", sessionId(newClient), jitter)
        Future.sleep(jitter)
      }.ensure {
        reconnect()
      }
    }
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
          logger.warning("ConnectionLoss to Zookeeper host. Session %s. Retrying", sessionId)
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
        if (closed) Future.Unit
        else safeRetry(go).transform {
          case Throw(e@KeeperException.SessionExpired(_)) =>
            // don't retry. The session has expired while trying to set the watch.
            // In case our activity is still active, notify the listener
            u() = Activity.Failed(e)
            Future.Unit

          case Throw(exc) =>
            logger.error("Operation failed with %s. Session %s", exc, sessionId)
            u() = Activity.Failed(exc)
            retryWithDelay { loop() }

          case Return(Watched(value, state)) =>
            val ok = Activity.Ok(value)
            retryBackoff.reset()
            u() = ok

            state.map { s =>
              s match {
                case WatchState.Pending => // No action to take

                case WatchState.Determined(_) => // No action to take

                case WatchState.SessionState(sessionState) if sessionState == SessionState.ConnectedReadOnly |
                  sessionState == SessionState.SaslAuthenticated |
                  sessionState == SessionState.SyncConnected =>
                  logger.info("Reacquiring watch on %s. Session: %s", sessionState, sessionId)
                  u() = ok

                case WatchState.SessionState(SessionState.Expired) =>
                  u() = Activity.Failed(new Exception("session expired"))

                case WatchState.SessionState(sessionState) if sessionState == SessionState.Disconnected |
                  sessionState == SessionState.NoSyncConnected =>
                  logger.warning(
                    "Intermediate Failure session state: %s. Session: %s. Data is now unavailable.",
                    sessionState, sessionId
                  )
                  u() = Activity.Failed(new Exception("" + sessionState))

                case WatchState.SessionState(sessionState) =>
                  logger.error("Unexpected session state %s. Session: %s", sessionState, sessionId)
                  u() = Activity.Failed(new Exception("" + sessionState))
              }
              s
            }.changes.filter {
              case WatchState.Pending => false

              case WatchState.Determined(_) => true

              case WatchState.SessionState(sessionState) if sessionState == SessionState.ConnectedReadOnly |
                sessionState == SessionState.SaslAuthenticated |
                sessionState == SessionState.SyncConnected =>
                true

              case WatchState.SessionState(SessionState.Expired) =>
                false

              case WatchState.SessionState(sessionState) if sessionState == SessionState.Disconnected |
                sessionState == SessionState.NoSyncConnected =>
                false

              case WatchState.SessionState(_) =>
                true
            }.toFuture().flatMap {
              case WatchState.Determined(_) => loop()
              case _ => retryWithDelay { loop() }
            }
        }
      }

      val pending = loop()
      Closable.make { _ =>
        closed = true
        pending.raise(new FutureCancelledException)
        Future.Unit
      }
    })

  private[this] def fireAndForget(go: => Future[Unit]): Unit = {
    val _ = go
  }

  private[this] def sessionId: String = sessionId(client.sample)
  private[this] def sessionId(wzk: Watched[ZooKeeperRW]): String =
    wzk.value.sessionId.toHexString

}
