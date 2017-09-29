package io.buoyant.k8s

import com.twitter.logging.Logger

private[k8s] case class PortMapLogger(nsName: String, serviceName: String) {
  def logDiff[A,B](oldPorts: Map[A, B], newPorts: Map[A, B]): Unit =
    if (log.isLoggable(Logger.TRACE)) {
      for {
        port <- oldPorts.keySet
        oldValue <- oldPorts.get(port)
      } newPorts.get(port) match {
        case Some(`oldValue`) =>
        // the key exists in the new state, but has the same value in both
        // the old and new states. skip it.
        case Some(newValue) =>
          log.trace(
            "k8s ns %s service %s remapped port %s from %s to %s",
            nsName, serviceName, port, oldValue, newValue
          )
        case None =>
          log.trace(
            "k8s ns %s service %s removed port mapping from %s to %s",
            nsName, serviceName, port, oldValue
          )
      }
      (newPorts -- oldPorts.keys).foreach { case (from, to) =>
        log.trace(
          "k8s ns %s service %s mapped port %s to %s",
          nsName, serviceName, to, from
        )
      }
    }
}