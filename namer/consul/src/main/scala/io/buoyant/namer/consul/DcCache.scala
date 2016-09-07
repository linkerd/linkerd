package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.consul.v1

/**
 * Contains all cached serviceMap responses and the mapping of names
 * to Addrs for a particular datacenter.
 */
private[consul] class DcCache(
  consulApi: v1.ConsulApi,
  name: String,
  domain: Option[String]
) {

  // Write access to `activity` must be synchronized to ensure
  // ordering for read/write blocks.
  private[this] val activityMu = new {}
  private[this] val activity: ActUp[Map[SvcKey, Var[Addr]]] = Var(Activity.Pending)
  def services: Var[Activity.State[Map[SvcKey, Var[Addr]]]] = activity

  @volatile private[this] var index = "0"
  private[this] def setIndex(idx: Option[String]) = idx match {
    case None =>
    case Some(idx) =>
      index = idx
  }

  private[this] def getServices(): Future[Set[SvcKey]] =
    consulApi.serviceMap(
      datacenter = Some(name),
      blockingIndex = Some(index),
      retry = true
    ).map(toServices)

  private[this] val toServices: v1.Indexed[Map[String, Seq[String]]] => Set[SvcKey] = {
    case v1.Indexed(services, idx) =>
      setIndex(idx)
      services.flatMap {
        case (svcName, tags) =>
          tags.map(tag => SvcKey(svcName, Some(tag))) :+ SvcKey(svcName, None)
      }.toSet
  }

  @volatile private[this] var stopped: Boolean = false

  private[this] def watchLoop(): Future[Unit] =
    if (stopped) Future.Unit
    else getServices().flatMap(updateAndWatch)

  private[this] val updateAndWatch: Set[SvcKey] => Future[Unit] = { updateKeys =>
    activityMu.synchronized {
      val cache = activity.sample() match {
        case Activity.Ok(svcs) => svcs
        case _ => Map.empty[SvcKey, Var[Addr]]
      }

      cache.foreach {
        case (k, _) if updateKeys(k) => // reuse this service below
        case (k, svc) =>
          // Observation of the service updates the service Addr appropriately.
          log.debug("consul deleted: %s", svc)
      }

      val updated = updateKeys.map { k =>
        val svc = cache.get(k) match {
          case Some(svc) => svc
          case None =>
            log.debug("consul added: %s", k)
            SvcAddr(consulApi, name, k, domain)
        }
        k -> svc
      }

      activity() = Activity.Ok(updated.toMap)
    }

    watchLoop()
  }

  private[this] val pending: Future[Unit] =
    getServices().flatMap(updateAndWatch).onFailure {
      case e: v1.UnexpectedResponse =>
        activityMu.synchronized {
          activity() = Activity.Ok(Map.empty)
        }

      case e: Throwable =>
        activityMu.synchronized {
          activity() = Activity.Failed(e)
        }
    }
}
