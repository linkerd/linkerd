package io.buoyant.k8s

private[k8s] trait EventLogging {
  def ns: String
  def srv: String

  @inline private[this] def logAction[A](verb: String, descriptor: => String, format: A => String)(value: A): Unit =
    log.ifTrace(s"k8s ns $ns service $srv $verb $descriptor ${format(value)}")

  protected def logActions[A](verb: String, descriptor: => String, format: A => String)(values: Iterable[A]): Unit =
    if (values.nonEmpty) {
      log.debug(
        "k8s ns %s service %s %s %ss",
        ns, srv, verb, descriptor
      )
      values.foreach(logAction(verb, descriptor, format))
    }

  private[this] def formatMapping[A, B](kv: (A, B)): String = kv match {
    case (name: String, port: Int) => s"'$name' to port $port"
    case (name: String, to) => s"'$name' to $to"
    case (fromPort, to) => s"from $fromPort to $to"
  }

  @inline def deletion[A, B](deleted: Map[A, B]): Unit =
    logActions("deleted", "port mapping", formatMapping)(deleted)

  @inline def addition[A, B](added: Map[A, B]): Unit =
    logActions("added", "port mapping", formatMapping)(added)

  protected def logModification[A](descriptor: String)(mods: Iterable[(A, A)]): Unit =
    if (mods.nonEmpty) {
      log.debug(
        "k8s ns %s service %s modified %ss",
        ns, srv, descriptor
      )
      for {
        (old, replacement) <- mods
      } logAction[A](s"replaced $old with", descriptor, _.toString)(replacement)
    }

  def modification[A, B](old: Map[A, B], replacement: Map[A, B]): Unit =
    if (old.nonEmpty && replacement.nonEmpty) {
      log.debug(
        "k8s ns %s service %s modified port mappings",
        ns, srv
      )
      for {
        key <- old.keySet.intersect(replacement.keySet)
        oldValue <- old.get(key)
        newValue <- replacement.get(key)
        if oldValue != newValue
      } log.ifTrace(s"k8s ns $ns service $srv remapped port '$key' from $oldValue to $newValue")
    }
}