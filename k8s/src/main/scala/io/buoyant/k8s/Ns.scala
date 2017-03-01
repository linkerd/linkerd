package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.EndpointsNamer.SvcCache
import io.buoyant.k8s.Ns.ObjectCache
import io.buoyant.k8s.v1beta1.{IngressList, IngressWatch, Ingress}

abstract class Ns[O <: KubeObject: Manifest, W <: Watch[O]: Manifest, L <: KubeList[O]: Manifest, Cache <: ObjectCache[O, W, L]](
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds),
  timer: Timer = DefaultTimer.twitter
) {
  // note that caches must be updated with synchronized
  private[this] val caches = Var[Map[String, Cache]](Map.empty[String, Cache])
  // XXX once a namespace is watched, it is watched forever.
  private[this] var _watches = Map.empty[String, Activity[Closable]]

  /**
   * Returns an Activity backed by a Future.  The resultant Activity is pending until the
   * original future is satisfied.  When the Future is successful, the Activity becomes
   * an Activity.Ok with a fixed value from the Future.  If the Future fails, the Activity
   * becomes an Activity.Failed and the Future is retried with the given backoff schedule.
   * Therefore, the legal state transitions are:
   *
   * Pending -> Ok
   * Pending -> Failed
   * Failed -> Failed
   * Failed -> Ok
   */
  private[this] def retryToActivity[T](go: => Future[T]): Activity[T] = {
    val state = Var[Activity.State[T]](Activity.Pending)
    _retryToActivity(backoff, state)(go)
    Activity(state)
  }

  private[this] def _retryToActivity[T](
    remainingBackoff: Stream[Duration],
    state: Var[Activity.State[T]] with Updatable[Activity.State[T]] = Var[Activity.State[T]](Activity.Pending)
  )(go: => Future[T]): Unit = {
    val _ = go.respond {
      case Return(t) =>
        state() = Activity.Ok(t)
      case Throw(e) =>
        state() = Activity.Failed(e)
        remainingBackoff match {
          case delay #:: rest =>
            val _ = Future.sleep(delay)(timer).onSuccess { _ => _retryToActivity(rest, state)(go) }
          case Stream.Empty =>
        }
    }
  }

  protected def mkResource(name: String): ListResource[O, W, L]

  protected def mkCache(name: String): Cache

  def get(name: String, labelSelector: Option[String]): Cache = synchronized {
    caches.sample.get(name) match {
      case Some(ns) => ns
      case None =>
        val ns = mkCache(name)
        val closable = retryToActivity { watch(name, labelSelector, ns) }
        _watches += (name -> closable)
        caches() = caches.sample + (name -> ns)
        ns
    }
  }

  val namespaces: Var[Set[String]] = caches.map(_.keySet)

  private[this] def watch(namespace: String, labelSelector: Option[String], cache: Cache): Future[Closable] = {
    val resource = mkResource(namespace)
    Trace.letClear {
      log.info("k8s initializing %s", namespace)
      resource.get().map { list =>
        cache.initialize(list)
        val (updates, closable) = resource.watch(
          labelSelector = labelSelector,
          resourceVersion = list.metadata.flatMap(_.resourceVersion)
        )
        // fire-and-forget this traversal over an AsyncStream that updates the services state
        val _ = updates.foreach(cache.update)
        closable
      }.onFailure { e =>
        log.error(e, "k8s failed to list endpoints")
      }
    }
  }
}

object Ns {
  abstract class ObjectCache[O <: KubeObject: Manifest, W <: Watch[O]: Manifest, L <: KubeList[O]: Manifest] {
    def initialize(list: L): Unit
    def update(event: W): Unit
  }

  type VarUp[T] = Var[T] with Updatable[T]

  abstract class NsListCache[O <: KubeObject: Manifest, W <: Watch[O]: Manifest, L <: KubeList[O]: Manifest, V, K](namespace: String) extends Ns.ObjectCache[O, W, L] {

    val state = Var[Activity.State[Map[K, VarUp[V]]]](Activity.Pending)

    val items: Activity[Map[K, VarUp[V]]] = Activity(state)

    def mkItem(item: O): Option[V]
    def updateItem(item: O): Option[V]
    def getName(item: O): Option[K]

    /**
     * Initialize a namespaces of services.  The activity is updated
     * once with the entire state of the namespace (i.e. not
     * incrementally service by service).
     */
    def initialize(items: L): Unit = {
      val initItems = items.items.flatMap { item =>
        mkItem(item).map { i => getName(item).get -> Var(i) }
      }

      synchronized {
        state() = Activity.Ok(initItems.toMap)
      }
    }

    def add(obj: O): Unit =
      for (item <- mkItem(obj)) synchronized {
        val name = getName(obj).get //todo
        log.debug("k8s ns %s added: %s", namespace, name)
        val items = state.sample() match {
          case Activity.Ok(items) => items
          case _ => Map.empty[K, VarUp[V]]
        }
        state() = Activity.Ok(items + (name -> Var(item)))
      }

    def modify(obj: O): Unit =
      for (name <- getName(obj)) synchronized {
        log.debug("k8s ns %s modified: %s", namespace, name)
        state.sample() match {
          case Activity.Ok(snap) =>
            snap.get(name) match {
              case None =>
                log.warning("k8s ns %s received modified watch for unknown resource %s", namespace, name)
              case Some(item) =>
                updateItem(obj) match {
                  case Some(i) => item() = i
                  case None => state() = Activity.Ok(snap - name)
                }
            }
          case _ =>
        }
      }

    def delete(obj: O): Unit =
      for (name <- getName(obj)) synchronized {
        log.debug("k8s ns %s deleted: %s", namespace, name)
        state.sample() match {
          case Activity.Ok(snap) =>
            for (svc <- snap.get(name)) {
              state() = Activity.Ok(snap - name)
            }

          case _ =>
        }
      }
  }

}
