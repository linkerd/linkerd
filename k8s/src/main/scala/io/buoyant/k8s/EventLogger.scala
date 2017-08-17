package io.buoyant.k8s

private[k8s] case class EventLogger(nsName: String, serviceName: String) {
  import EventLogger._
  def addition[A: Loggable](additions: Iterable[A]): Unit = {
    log.debug(
      "k8s ns %s service %s added %ss",
      nsName, serviceName,
      implicitly[Loggable[A]].descriptor
    )
    additions.foreach(implicitly[Loggable[A]].logAddition(nsName, serviceName))
  }

  def deletion[A: Loggable](deletions: Iterable[A]): Unit = {
    log.debug(
      "k8s ns %s service %s deleted %ss",
      nsName, serviceName,
      implicitly[Loggable[A]].descriptor
    )
    deletions.foreach(implicitly[Loggable[A]].logDeletion(nsName, serviceName))
  }

  def modification[A: Loggable](mods: Iterable[(A, A)]): Unit = {
    log.debug(
      "k8s ns %s service %s modified %ss",
      nsName, serviceName,
      implicitly[Loggable[A]].descriptor
    )
    mods.foreach(implicitly[Loggable[A]].logModification(nsName, serviceName).tupled)
  }
}

private[k8s] object EventLogger {
  trait Loggable[A] {
    def format(value: A): String = value.toString
    def descriptor: String
    def logAction(verb: String)(nsName: String, serviceName: String)(value: A): Unit =
      log.ifTrace(
        "k8s ns %s service %s %s %s %s",
        nsName,
        serviceName,
        verb,
        descriptor,
        format(value)
      )
    val logAddition: (String, String) => A => Unit = logAction("added")
    val logDeletion: (String, String) => A => Unit = logAction("deleted")
    val logModification:  (String, String) => (A, A) => Unit =
      (nsName, serviceName) => (old, replacement) =>
        logAction(s"replaced $descriptor $old with ")(nsName, serviceName)(replacement)

  }
  implicit def LoggableMapping[A, B]: Loggable[(A, B)] = new Loggable[(A, B)] {
    override val descriptor: String = "port mapping"
    override def format(value: (A, B)): String = s"${value._1} to ${value._2}"
  }
}