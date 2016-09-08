package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.consul.v1

/**
 * Contains all cached serviceMap responses and the mapping of names
 * to SvcCaches for a particular datacenter.
 */
private[consul] class DcCache(
  consulApi: v1.ConsulApi,
  name: String,
  domain: Option[String]
) {

  // Write access to `activity` must be synchronized because of read/write blocks.
  private[this] val activity: ActUp[Map[SvcKey, SvcCache]] = Var(Activity.Pending)

  def services: Var[Activity.State[Map[SvcKey, SvcCache]]] = activity

  @volatile private[this] var index = "0"
  private[this] def setIndex(idx: Option[String]) = idx match {
    case None =>
    case Some(idx) =>
      index = idx
  }

  private[this] val indexed: v1.Indexed[Map[String, Seq[String]]] => Set[SvcKey] = {
    case v1.Indexed(services, idx) =>
      setIndex(idx)
      services.flatMap {
        case (svcName, tags) =>
          tags.map(tag => SvcKey(svcName, Some(tag))) :+ SvcKey(svcName, None)
      }.toSet
  }

  private[this] def mkRequest(): Future[Set[SvcKey]] =
    consulApi.serviceMap(
      datacenter = Some(name),
      blockingIndex = Some(index),
      retry = true
    ).map(indexed)

  def clear(): Unit = synchronized {
    activity.sample() match {
      case Activity.Ok(snap) =>
        for (svc <- snap.values) {
          svc.clear()
        }
      case _ =>
    }
    activity() = Activity.Pending
  }

  def update(updateKeys: Set[SvcKey]): Future[Unit] = {
    synchronized {
      val orig = activity.sample() match {
        case Activity.Ok(svcs) => svcs
        case _ => Map.empty[SvcKey, SvcCache]
      }

      orig.foreach {
        case (k, _) if updateKeys(k) => // reuse this service below
        case (k, svc) =>
          log.debug("consul deleted: %s", svc)
          svc.clear()
      }

      val updated = updateKeys.map { k =>
        val svc = orig.get(k) match {
          case Some(svc) => svc
          case None =>
            log.debug("consul added: %s", k)
            mkSvc(k)
        }
        k -> svc
      }

      activity() = Activity.Ok(updated.toMap)
    }

    mkRequest().flatMap(update)
  }

  private[this] def mkSvc(svcKey: SvcKey): SvcCache =
    new SvcCache(consulApi, name, svcKey, domain)

  private[this] val running: Future[Unit] =
    mkRequest().flatMap { svcKeys =>
      val services = svcKeys.map { k => k -> mkSvc(k) }.toMap
      synchronized {
        activity() = Activity.Ok(services)
      }
      mkRequest().flatMap(update)
    }.handle {
      case e: v1.UnexpectedResponse => activity() = Activity.Ok(Map.empty)
      case e: Throwable => activity() = Activity.Failed(e)
    }

}
