package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.consul._
import io.buoyant.consul.v1.{ServiceNode, UnexpectedResponse}
import io.buoyant.namer.Metadata

/**
 * Contains all cached serviceMap responses and the mapping of names
 * to SvcCaches for a particular datacenter.
 */
private[consul] class DcCache(
  consulApi: v1.ConsulApi,
  agentApi: v1.AgentApi,
  name: String,
  activity: ActUp[Map[SvcKey, SvcCache]],
  setHost: Boolean
) {

  def services: Var[Activity.State[Map[SvcKey, SvcCache]]] = activity

  private[this] var index = "0"
  val running =
    mkRequest().flatMap { svcKeys =>
      val services = svcKeys.map { k => k -> mkSvc(k) }.toMap
      synchronized {
        activity() = Activity.Ok(services)
      }
      mkRequest().flatMap(update)
    }.handle {
      case e: UnexpectedResponse => activity() = Activity.Ok(Map.empty)
      case e: Throwable => activity() = Activity.Failed(e)
    }

  private[this] def setIndex(idx: Option[String]) = idx match {
    case None =>
    case Some(idx) =>
      index = idx
  }

  def mkRequest(): Future[Seq[SvcKey]] =
    consulApi
      .serviceMap(datacenter = Some(name), blockingIndex = Some(index), retry = true)
      .map { indexedSvcs =>
        setIndex(indexedSvcs.index)
        indexedSvcs.value.flatMap {
          case (svcName, tags) =>
            tags.map(tag => SvcKey(svcName, Some(tag))) :+ SvcKey(svcName, None)
        }.toSeq
      }

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

  def update(serviceKeys: Seq[SvcKey]): Future[Unit] = {
    synchronized {
      val svcs = services.sample() match {
        case Activity.Ok(svcs) => svcs
        case _ => Map.empty[SvcKey, SvcCache]
      }

      serviceKeys.foreach { key =>
        if (!svcs.contains(key))
          add(key)
      }

      svcs.foreach {
        case (key, _) =>
          if (!serviceKeys.contains(key))
            delete(key)
      }
    }

    mkRequest().flatMap(update)
  }

  private[this] def mkSvc(svcKey: SvcKey): SvcCache =
    SvcCache(consulApi, agentApi, name, svcKey, Var(Addr.Pending), setHost)

  private[this] def add(serviceKey: SvcKey): Unit = {
    log.debug("consul added: %s", serviceKey)
    val svcs = services.sample() match {
      case Activity.Ok(svcs) => svcs
      case _ => Map.empty[SvcKey, SvcCache]
    }
    activity() = Activity.Ok(svcs + (serviceKey -> mkSvc(serviceKey)))
  }

  private[this] def delete(serviceKey: SvcKey): Unit = {
    log.debug("consul deleted: %s", serviceKey)
    val svcs = services.sample() match {
      case Activity.Ok(svcs) => svcs
      case _ => Map.empty[SvcKey, SvcCache]
    }
    svcs.get(serviceKey) match {
      case Some(svc) =>
        svc.clear()
        activity() = Activity.Ok(svcs - serviceKey)
      case _ =>
    }
  }

}
