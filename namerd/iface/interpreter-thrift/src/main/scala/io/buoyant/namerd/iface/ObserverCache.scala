package io.buoyant.namerd.iface

import com.google.common.cache.{CacheBuilder, RemovalListener, RemovalNotification}
import com.twitter.util.{Return, Throw, Try}
import io.buoyant.namerd.iface.ThriftNamerInterface.Observer
import java.util.concurrent.ConcurrentHashMap

class MaximumObservationsReached(maxObservations: Int)
  extends Exception(s"The maximum number of concurrent observations has been reached ($maxObservations)")

class ObserverCache[K, T](activeCapacity: Int, inactiveCapacity: Int)(mkObserver: K => Observer[T]) {

  def get(key: K): Try[Observer[T]] = {
    Option(Return(activeCache.get(key))).getOrElse {
      synchronized {
        Option(activeCache.get(key))
          .map(Return(_))
          .getOrElse(makeActive(key))
      }
    }
  }

  private[this] val activeCache = new ConcurrentHashMap[K, Observer[T]]
  private[this] val inactiveCache = CacheBuilder.newBuilder()
    .maximumSize(inactiveCapacity)
    .removalListener(new RemovalListener[K, Observer[T]] {
      override def onRemoval(notification: RemovalNotification[K, Observer[T]]): Unit =
        notification.getValue.close()
    })
    .build[K, Observer[T]]()

  private[this] def makeActive(key: K): Try[Observer[T]] =
    if (activeCache.size < activeCapacity) {
      val obs = Option(inactiveCache.getIfPresent(key)).getOrElse {
        mkObserver(key)
      }
      inactiveCache.invalidate(key)
      activeCache.put(key, obs)
      obs.nextValue().ensure {
        makeInactive(key, obs)
      }
      Return(obs)
    } else {
      Throw(new MaximumObservationsReached(activeCapacity))
    }

  private[this] def makeInactive(key: K, obs: Observer[T]): Unit = {
    synchronized {
      activeCache.remove(key)
      inactiveCache.put(key, obs)
    }
  }
}
