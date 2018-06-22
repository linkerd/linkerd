package io.buoyant.namer.consul

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util._
import io.buoyant.consul.v1
import io.buoyant.namer.{InstrumentedActivity, InstrumentedVar}
import scala.Function.untupled

private[consul] object LookupCache {
  val DefaultBackoffs: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 5.seconds)
}

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
  weights: Map[String, Double] = Map.empty,
  stats: StatsReceiver = NullStatsReceiver
) {

  private[this] val localDcMoniker = ".local"

  private[this] val lookupCounter = stats.counter("lookups")
  private[this] val service: StatsReceiver = stats.scope("service")
  private[this] val cachedCounter = service.counter("cached")
  private[this] val serviceStats = SvcAddr.Stats(service)

  private[this] val cachedLookup: (String, SvcKey, Path, Path) => InstrumentedActivity[NameTree[Name.Bound]] =
    untupled(Memoize[(String, SvcKey, Path, Path), InstrumentedActivity[NameTree[Name.Bound]]] {
      case (dc, key, id, residual) =>
        val pollState = SvcAddr.mkConsulPollState
        val addrFuture: Future[InstrumentedVar[Addr]] = resolveDc(dc).join(domain).map {
          case ((dcName, domainOption)) =>
            SvcAddr(
              consulApi,
              LookupCache.DefaultBackoffs,
              dcName,
              key,
              domainOption,
              consistency = consistency,
              preferServiceAddress = preferServiceAddress,
              weights,
              serviceStats,
              Some(pollState)
            )
        }

        val instrumentedObsv = InstrumentedActivity[NameTree[Name.Bound]] { observationState =>
          val closableFuture = addrFuture.transform {
            case Return(addr) =>
              val observationClosable = addr.underlying.changes.respond {
                case Addr.Neg => observationState.update(Activity.Ok(NameTree.Neg))
                case Addr.Pending => observationState.update(Activity.Pending)
                case Addr.Failed(why) => observationState.update(Activity.Failed(why))
                case Addr.Bound(_, _) => observationState.update(Activity.Ok(NameTree.Leaf(Name.Bound(addr.underlying, id, residual))))
              }
              Future.value(observationClosable)
            case Throw(cause) =>
              // We probably failed to fetch agent config. This is critical.
              // TODO: if this has happened only restart can restore consul namer to working state. Throw exception instead?
              observationState.update(Activity.Failed(cause))
              Future.value(Closable.nop)
          }

          Closable.make { deadline =>
            closableFuture.flatMap(_.close(deadline))
          }
        }

        cachedCounter.incr()
        instrumentedObsv
    })

  def apply(
    dc: String,
    key: SvcKey,
    id: Path,
    residual: Path
  ): InstrumentedActivity[NameTree[Name.Bound]] = {
    log.debug("consul lookup: %s %s", dc, id.show)
    lookupCounter.incr()
    cachedLookup(dc, key, id, residual)
  }

  private[this] def resolveDc(datacenter: String): Future[String] =
    if (datacenter == localDcMoniker)
      localDc.map(_.getOrElse(datacenter))
    else Future.value(datacenter)

  private[this] lazy val agentConfig: Future[Option[v1.Config]] = agentApi.localAgent(retry = true).map(_.Config)

  private[this] lazy val domain: Future[Option[String]] =
    if (setHost) {
      agentConfig.map { config =>
        val dom = config.flatMap(_.Domain).getOrElse("consul")
        Some(dom.stripPrefix(".").stripSuffix("."))
      }
    } else Future.value(None)

  private[this] lazy val localDc: Future[Option[String]] = agentConfig.map(_.flatMap(_.Datacenter))
}
