package io.buoyant.k8s

private[k8s] trait Loggable {
  def logAction(verb: String)(nsName: String, serviceName: String): Unit
  val logAddition: (String, String) => Unit = logAction("added")
  val logDeletion: (String, String) => Unit = logAction("deleted")
}

private[k8s] object EventLogger {
  implicit class LoggableMapping[A, B](val mapping: (A, B))
  extends Loggable {
    override def logAction(verb: String)(nsName: String, serviceName: String): Unit =
      log.debug(
        "k8s ns %s service %s %s port mapping %s to %s",
        nsName, serviceName, verb, mapping._1, mapping._2
      )
  }
}

private[k8s] case class EventLogger(nsName: String, serviceName: String) {
  import EventLogger._

  def addition(additions: Iterable[Loggable]): Unit =
    additions.foreach(_.logAddition(nsName, serviceName))

  def deletion(deletions: Iterable[Loggable]): Unit =
    deletions.foreach(_.logDeletion(nsName, serviceName))

  def modification[T <: Loggable](mods: Iterable[(T, T)]): Unit =
    for { (old, replacement) <- mods }
      log.debug(
        "k8s ns %s service %s replaced %s with %s",
        nsName, serviceName, old, replacement
      )

  def addition(additions: Map[_, _]): Unit =
    additions.foreach(_.logAddition(nsName, serviceName))

  def deletion(deletions: Map[_, _]): Unit =
    deletions.foreach(_.logDeletion(nsName, serviceName))

  def modification[A, B](mods: Map[(A, B), (A, B)]): Unit =
    for { (old, replacement) <- mods }
      log.debug(
        "k8s ns %s service %s %s remapped port %s from %s to %s",
        nsName, serviceName, old._1, old._2, replacement._2
      )
}