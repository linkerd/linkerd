package io.buoyant.k8s

import com.twitter.logging.Logger

private[k8s] trait EventLogging {
  def ns: String
  def srv: String
  
  @inline private[this] def logAction[A](verb: String, descriptor: String, format: A => String)(value: A): Unit =
    log.ifTrace(s"k8s ns $ns service $srv $verb $descriptor ${format(value)}")

  @inline private[this] def formatMapping[A, B](kv: (A, B)): String = kv match {
    case (name: String, port: Int) => s"'$name' to port $port"
    case (name: String, to) => s"'$name' to $to"
    case (fromPort, to) => s"from $fromPort to $to"
  }

  @inline private[this] def defaultFormat[A](a: A): String = a.toString

  protected def mkDeletion[A](
    descriptor: String,
    format: A => String = defaultFormat[A](_)
  )(
    deleted: Iterable[A]
  ): Unit =
    if (deleted.nonEmpty) {
      log.debug(
        "k8s ns %s service %s deleted %ss",
        ns, srv, descriptor
      )
      if (log.isLoggable(Logger.TRACE)) {
        deleted.foreach(logAction("deleted", descriptor, format))
      }
    }

  def deletion[A, B](deleted: Map[A, B]): Unit =
    mkDeletion("port mapping", formatMapping)(deleted)

  protected def mkNewState[A](
    descriptor: String,
    format: A => String = defaultFormat[A](_)
  )(
    old: Set[A],
    newState: Set[A]
  ): Unit =
    if (old.nonEmpty && old != newState) {
      log.debug(
        "k8s ns %s service %s modified %ss",
        ns, srv, descriptor
      )
      if (log.isLoggable(Logger.TRACE)) {
        // added
        (newState -- old).foreach {
          logAction("added", descriptor, format)
        }
        // deleted
        (old -- newState).foreach {
          logAction("deleted", descriptor, format)
        }

      }
    }

  def newState[A, B](old: Map[A, B], newState: Map[A, B]): Unit =
    if (old.nonEmpty && old != newState) {
      log.debug(
        "k8s ns %s service %s modified port mappings",
        ns, srv
      )
      if (log.isLoggable(Logger.TRACE)) {

        val (remapped: Set[Option[String]], removed: Set[Option[String]]) = (for {
          key <- old.keySet
          oldValue <- old.get(key)
        } yield newState.get(key) match {
          case None =>
            (None, Some(s"k8s ns $ns service $srv removed port mapping '$key' to $oldValue"))
          case Some(newValue) =>
            (Some(s"k8s ns $ns service $srv remapped port '$key' from $oldValue to $newValue"), None)
        }).unzip
        val added = newState -- old.keys

        added.foreach { case (key, value) =>
            log.trace(
              "k8s ns %s service %s added port mapping from '%s' to %s",
              ns, srv,
              key, value
            )
        }
        remapped.flatten.foreach(log.trace(_))
        removed.flatten.foreach(log.trace(_))

      }
    }
}