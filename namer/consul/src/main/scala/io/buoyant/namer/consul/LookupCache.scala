package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.consul.v1

/**
 * A helper supporting service resolution in consul, caching results
 * and watching for new updates.
 */
private[consul] class LookupCache(
  consulApi: v1.ConsulApi,
  agentApi: v1.AgentApi,
  setHost: Boolean = false
) {

  def apply(
    dcName: String,
    svcKey: SvcKey,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = {
    log.debug("consul lookup: %s %s", id.show)
    Dc.watch(dcName).map { services =>
      services.get(svcKey) match {
        case None =>
          log.debug("consul dc %s service %s missing", dcName, svcKey)
          NameTree.Neg

        case Some(addr) =>
          log.debug("consul ns %s service %s found + %s", dcName, svcKey, residual.show)
          NameTree.Leaf(Name.Bound(addr, id, residual))
      }
    }
  }

  // Note that this activity is never recomputed.  We simply do a
  // lookup for the domain and then wrap it an activity for
  // convenience.
  private[this] lazy val domain: Activity[Option[String]] =
    if (setHost) {
      Activity.future(agentApi.localAgent(retry = true)).map { la =>
        val dom = la.Config.flatMap(_.Domain).getOrElse("consul")
        Some(dom.stripPrefix(".").stripSuffix("."))
      }
    } else Activity.value(None)

  /**
   * Contains all cached responses from the Consul API
   */
  private[this] object Dc {
    type Services = Activity[Map[SvcKey, Var[Addr]]]
    private[this] val activity: ActUp[Map[String, Services]] =
      Var(Activity.Pending)

    def watch(dc: String): Services =
      domain.flatMap(get(dc, _))

    /**
     * Returns existing datacenter cache with that name
     * or creates a new one
     */
    private[this] def get(name: String, domain: Option[String]): Services =
      synchronized {
        activity.sample() match {
          case Activity.Ok(snap) => snap.getOrElse(name, mkAndUpdate(snap, name, domain))
          case _ => mkAndUpdate(Map.empty, name, domain)
        }
      }

    private[this] def mkAndUpdate(
      cache: Map[String, Services],
      name: String,
      domain: Option[String]
    ): Services = {
      val dc = DcServices(consulApi, name, domain)
      activity() = Activity.Ok(cache + (name -> dc))
      dc
    }
  }
}
