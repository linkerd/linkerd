package io.buoyant.k8s

import com.twitter.logging.Logger

private[k8s] trait EventLogging {
  def ns: String
  def srv: String
  
  @inline 
  protected def logAction[A](
    noun: String,
    format: A => String = (a: A) => a.toString
  )(
    verb: String
  )(
    value: A
  ): Unit =
    log.trace(
      s"k8s ns %s service %s %s %s %s",
      ns, srv, 
      verb, noun,
      format(value)
    )

  @inline
  private[this] def formatMapping(kv: (Any, Any)): String =
    kv match {
      case (name: String, port: Int) => s"'$name' to port $port"
      case (name: String, (oldTo, newTo)) => s"'$name' from $oldTo to $newTo"
      case (name: String, to) => s"'$name' to $to"
      case (fromPort, (oldTo, newTo)) => s"$fromPort from $oldTo to $newTo"
      case (fromPort, to) => s"from $fromPort to $to"
    }

  protected def _newState[A](
    noun: String,
    wasAdded: A => Unit,
    wasDeleted: A => Unit
  )(
    oldState: Set[A],
    newState: Set[A]
  ): Unit =
    if (oldState != newState) {
      log.debug(
        "k8s ns %s service %s modified %ss",
        ns, srv, noun
      )
      if (log.isLoggable(Logger.TRACE)) {
        (newState -- oldState).foreach { wasAdded }
        (oldState -- newState).foreach { wasDeleted }
      }
    }

  def deletion(noun: String = ""): Unit =
    log.debug("k8s ns %s service %s deleted %s", ns, srv, noun)

  private val mappingWas = logAction("port mapping", formatMapping)(_)
  private val mappingWasAdded = mappingWas("added")
  private val mappingWasDeleted = mappingWas("added")
  private val wasRemapped = logAction("port", formatMapping)("remapped")(_)

  def newState[A, B](oldState: Map[A, B], newState: Map[A, B]): Unit =
    if (oldState != newState) {
      log.debug(
        "k8s ns %s service %s modified port mappings",
        ns, srv
      )
      if (log.isLoggable(Logger.TRACE)) {

        var remapped = Set[(A, (B, B))]()
        var removed = Set[(A, B)]()
        for {
          key <- oldState.keySet
          oldValue <- oldState.get(key)
        } newState.get(key) match {
          case Some(`oldValue`) =>
            // the key exists in the new state, but has the same value in both
            // the old and new states. skip it.
          case Some(newValue) =>
            // the key exists in both states, but has a different value in the
            // new state. add to `remapped`.
            remapped += key -> (oldValue, newValue)
          case None =>
            // the mapping no longer exists in the new state, so add it to
            // `removed`.
            removed += key -> oldValue
        }
        val added = newState -- oldState.keys

        added.foreach { mappingWasAdded }
        removed.foreach { mappingWasDeleted }
        remapped.foreach { wasRemapped }
      }
    }
}