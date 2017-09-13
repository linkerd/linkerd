package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util._
import io.buoyant.consul.v1
import io.buoyant.namer.Metadata
import scala.Function.untupled

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
  private[this] val serviceStats = SvcAddr.Stats(stats.scope("service"))

  private[this] val cachedLookup: (String, SvcKey, Path, Path) => Activity[NameTree[Name]] =
    untupled(Memoize[(String, SvcKey, Path, Path), Activity[NameTree[Name]]] {
      case (dc, key, id, residual) =>
        resolveDc(dc).join(domain).flatMap {
          case ((dcName, domainOption)) =>
            val addr = SvcAddr(
              consulApi,
              dcName,
              key,
              domainOption,
              consistency = consistency,
              preferServiceAddress = preferServiceAddress,
              serviceStats
            )
            log.debug("consul ns %s service %s found + %s", dc, key, residual.show)

            val stateVar: Var[Activity.State[NameTree[Name.Bound]]] = addr.map {
              case Addr.Neg => Activity.Ok(NameTree.Neg)
              case Addr.Pending => Activity.Pending
              case Addr.Failed(why) => Activity.Failed(why)
              case Addr.Bound(_, _) =>
                Activity.Ok(NameTree.Leaf(Name.Bound(addr, id, residual)))
            }
            new Activity(stateVar)
        }
    })

  def apply(
    dc: String,
    key: SvcKey,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = {
    log.debug("consul lookup: %s %s", dc, id.show)
    lookupCounter.incr()
    cachedLookup(dc, key, id, residual)
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

}
