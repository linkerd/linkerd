package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.stats.{Counter, StatsReceiver, NullStatsReceiver}
import com.twitter.util._
import io.buoyant.consul.v1

/**
 * A helper supporting service resolution in consul, caching
 * observations so that they may be shared between lookups.
 */
private[consul] class LookupCache(
  consulApi: v1.ConsulApi,
  agentApi: v1.AgentApi,
  setHost: Boolean = false,
  stats: StatsReceiver = NullStatsReceiver
) {

  private[this] val lookupCounter = stats.counter("lookups")

  def apply(
    dc: String,
    key: SvcKey,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = {
    log.debug("consul lookup: %s %s", id.show)
    lookupCounter.incr()

    Dc.watch(dc).map { services =>
      services.get(key) match {
        case None =>
          log.debug("consul dc %s service %s missing", dc, key)
          NameTree.Neg

        case Some(addr) =>
          log.debug("consul ns %s service %s found + %s", dc, key, residual.show)
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
    // Access to `activity` needs to be synchronized to preserve
    // ordering safety for read-writes (i.e. sample() and update()).
    private[this] val activity: ActUp[Map[String, Activity[Map[SvcKey, Var[Addr]]]]] =
      Var(Activity.Pending)

    def watch(dc: String): Activity[Map[SvcKey, Var[Addr]]] =
      domain.flatMap(get(dc, _))

    /**
     * Returns existing datacenter cache with that name
     * or creates a new one
     */
    private[this] def get(name: String, domain: Option[String]): Activity[Map[SvcKey, Var[Addr]]] =
      synchronized {
        activity.sample() match {
          case Activity.Ok(snap) => snap.getOrElse(name, mkAndUpdate(snap, name, domain))
          case _ => mkAndUpdate(Map.empty, name, domain)
        }
      }

    private[this] val dcStats = DcServices.Stats(stats)

    private[this] def mkAndUpdate(
      cache: Map[String, Activity[Map[SvcKey, Var[Addr]]]],
      name: String,
      domain: Option[String]
    ): Activity[Map[SvcKey, Var[Addr]]] = {
      val dc = DcServices(consulApi, name, domain, dcStats)
      activity() = Activity.Ok(cache + (name -> dc))
      dc
    }
  }
}
