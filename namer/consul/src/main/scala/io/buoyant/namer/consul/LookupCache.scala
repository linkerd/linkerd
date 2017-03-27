package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
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
  consistency: Option[v1.ConsistencyMode] = None,
  preferServiceAddress: Option[Boolean] = None,
  stats: StatsReceiver = NullStatsReceiver
) {

  private[this] val localDcMoniker = ".local"

  private[this] val lookupCounter = stats.counter("lookups")

  def apply(
    dc: String,
    key: SvcKey,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = {
    log.debug("consul lookup: %s %s", dc, id.show)
    lookupCounter.incr()

    resolveDc(dc).flatMap(Dc.watch).flatMap { services =>
      services.get(key) match {
        case None =>
          log.debug("consul dc %s service %s missing", dc, key)
          Activity.value(NameTree.Neg)

        case Some(addr) =>
          log.debug("consul ns %s service %s found + %s", dc, key, residual.show)
          val stateVar: Var[Activity.State[NameTree[Name.Bound]]] = addr.map {
            case Addr.Neg => Activity.Ok(NameTree.Neg)
            case _ => Activity.Ok(NameTree.Leaf(Name.Bound(addr, id, residual)))
          }
          new Activity(stateVar)
      }
    }
  }

  private[this] def resolveDc(datacenter: String): Activity[String] =
    if (datacenter == localDcMoniker)
      localDc.map(_.getOrElse(datacenter))
    else Activity.value(datacenter)

  // Note that this 3 activities are never recomputed.  We simply do a
  // lookup for the domain and then wrap it an activity for
  // convenience.
  private[this] lazy val agentConfig: Activity[Option[v1.Config]] =
    Activity.future(agentApi.localAgent(retry = true)).map(_.Config)

  private[this] lazy val domain: Activity[Option[String]] =
    if (setHost) {
      agentConfig.map { config =>
        val dom = config.flatMap(_.Domain).getOrElse("consul")
        Some(dom.stripPrefix(".").stripSuffix("."))
      }
    } else Activity.value(None)

  private[this] lazy val localDc = agentConfig.map(_.flatMap(_.Datacenter))

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
      val dc = DcServices(consulApi, name, domain, consistency, preferServiceAddress, dcStats)
      activity() = Activity.Ok(cache + (name -> dc))
      dc
    }
  }
}
