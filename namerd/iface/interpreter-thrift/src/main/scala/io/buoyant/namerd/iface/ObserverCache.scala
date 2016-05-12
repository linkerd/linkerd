package io.buoyant.namerd.iface

import com.google.common.cache.{CacheBuilder, RemovalListener, RemovalNotification}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.{Return, Throw, Try}
import io.buoyant.namerd.iface.ThriftNamerInterface.Observer
import java.util.concurrent.{Callable, ConcurrentHashMap}

class MaximumObservationsReached(maxObservations: Int)
  extends Exception(s"The maximum number of concurrent observations has been reached ($maxObservations)")

/**
 * A cache for Observer[T].  ObserverCache contains two levels of caching: active and inactive.
 *
 * The active cache is for Observers that have current outstanding requests against them.
 * Therefore, any Observer in the active cache must not be closed as this would cause the
 * outstanding requests to hang.
 *
 * The inactive cache is for Observers without outstanding requests against them.  These Observers
 * are kept open while in the inactive cache in case they receive a request and need to be moved
 * to the active cache.  The inactive cache has LRU eviction and Observers are closed upon
 * eviction.  Since Observers in the inactive cache have no outstanding requests against them,
 * this is safe to do.
 *
 * When get is called, the Observer for that key is moved to the active cache if it exists or
 * created and placed in the active cache if it does not exist.  When the value of an Observer
 * in the active cache changes, the Observer is moved to the inactive cache.  If the inactive
 * cache is full at that time, an older inactive Observer will be evicted.
 *
 * @param activeCapacity The maximum size of the active cache.  If get would cause the active cache
 *                       to exceed this size, a MaximumObservationsReached exception is returned
 *                       instead.
 * @param inactiveCapacity The maximum size of the inactive cache.  LRU eviction is used to
 *                         maintain this constraint.
 * @param mkObserver The function to use to create new Observers if they are not in either cache.
 */
class ObserverCache[K <: AnyRef, T](
  activeCapacity: Int,
  inactiveCapacity: Int,
  stats: StatsReceiver,
  mkObserver: K => Observer[T]
) {

  def get(key: K): Try[Observer[T]] =
    Option(activeCache.get(key)).map(Return(_)).getOrElse {
      synchronized {
        // now that we have entered the synchronized block we again check that key hasn't entered
        // the active cache
        Option(activeCache.get(key))
          .map(Return(_))
          .getOrElse(makeActive(key))
      }
    }

  // ConcurrentHashMap is used to make reads lockless, but all updates are explicitly synchronized
  private[this] val activeCache = new ConcurrentHashMap[K, Observer[T]]
  private[this] val inactiveCache = CacheBuilder.newBuilder()
    .maximumSize(inactiveCapacity)
    .removalListener(new RemovalListener[K, Observer[T]] {
      override def onRemoval(notification: RemovalNotification[K, Observer[T]]): Unit =
        if (notification.wasEvicted) {
          val _ = notification.getValue.close()
        }
    })
    .build[K, Observer[T]]()

  private[this] val activeSize = stats.addGauge("active")(activeCache.size)
  private[this] val inactiveSize = stats.addGauge("inactive")(inactiveCache.size)

  private[this] def makeActive(key: K): Try[Observer[T]] = synchronized {
    if (activeCache.size < activeCapacity) {
      val obs = Option(inactiveCache.getIfPresent(key)).getOrElse {
        mkObserver(key)
      }
      activeCache.put(key, obs)
      inactiveCache.invalidate(key)
      obs.nextValue.ensure {
        makeInactive(key, obs)
      }
      Return(obs)
    } else {
      Throw(new MaximumObservationsReached(activeCapacity))
    }
  }

  private[this] def makeInactive(key: K, obs: Observer[T]): Unit = synchronized {
    activeCache.remove(key)
    // insert obs into the inactive cache if it's not present
    val _ = inactiveCache.get(
      key, new Callable[Observer[T]] {
      def call = obs
    }
    )
  }
}
