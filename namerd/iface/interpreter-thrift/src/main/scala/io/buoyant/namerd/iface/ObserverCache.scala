package io.buoyant.namerd.iface

import com.twitter.util.{Return, Throw, Try}
import io.buoyant.namerd.iface.ThriftNamerInterface.Observer
import java.util.concurrent.ConcurrentHashMap

class MaximumObservationsReached(maxObservations: Int)
  extends Exception(s"The maximum number of concurrent observations has been reached ($maxObservations)")

class ObserverCache[K, T](maxObservations: Int)(mkObserver: K => Observer[T]) {

  def get(key: K): Try[Observer[T]] = {
    Option(Return(activeCache.get(key))).getOrElse {
      synchronized {
        makeActive(key)
      }
    }
  }

  private[this] val activeCache = new ConcurrentHashMap[K, Observer[T]]
  private[this] val inactiveCache = new ConcurrentHashMap[K, Observer[T]]

  private[this] def currentObservations = activeCache.size + inactiveCache.size

  private[this] def makeActive(key: K): Try[Observer[T]] =
    Option(Return(inactiveCache.get(key))).getOrElse {
      if (currentObservations < maxObservations)
        Return(mkObserver(key))
      else
        Throw(new MaximumObservationsReached(maxObservations))
    }.onSuccess { obs =>
      activeCache.put(key, obs)
      obs.nextValue().ensure {
        makeInactive(key, obs)
      }
    }

  private[this] def makeInactive(key: K, obs: Observer[T]): Unit = {
    activeCache.remove(key)
    inactiveCache.put(key, obs)
  }
}
