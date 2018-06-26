package io.buoyant.namer.consul

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util._
import io.buoyant.consul.v1
import io.buoyant.namer.{InstrumentedActivity, InstrumentedVar}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

private[consul] object LookupCache {
  type DataCenterName = String

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
  stats: StatsReceiver = NullStatsReceiver,
  pathParser: Path => Option[(LookupCache.DataCenterName, SvcKey, Path, Path)]
) {

  private[this] val localDcMoniker = ".local"

  private[this] val lookupCounter = stats.counter("lookups")
  private[this] val service: StatsReceiver = stats.scope("service")
  private[this] val cachedCounter = service.counter("cached")
  private[this] val serviceStats = SvcAddr.Stats(service)

  /*
   * use a shared mutex only on write, the ConcurrentHM impl should guarantee
   * happens-before semantic of reads wrt to updates (as per docs)
   */
  private[this] val lookupStatusMu = new {}
  private[this] val lookupStatus = new ConcurrentHashMap[Path, InstrumentedBind]()

  private[consul] def status: Map[Path, InstrumentedBind] = lookupStatus.asScala.toMap

  private[this] val lookup: (String, SvcKey, Path, Path) => InstrumentedBind =
    (dc, key, id, residual) => {
      val pollState = SvcAddr.mkConsulPollState
      val addrFuture: Future[Var[Addr]] = resolveDc(dc).join(domain).map {
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
            pollState
          )
      }

      val instrumentedObsv = InstrumentedActivity[NameTree[Name.Bound]] { observationState =>
        val closableFuture = addrFuture.transform {
          case Return(addr) =>
            val observationClosable = addr.changes.respond {
              case Addr.Neg => observationState.update(Activity.Ok(NameTree.Neg))
              case Addr.Pending => observationState.update(Activity.Pending)
              case Addr.Failed(why) => observationState.update(Activity.Failed(why))
              case Addr.Bound(_, _) => observationState.update(Activity.Ok(NameTree.Leaf(Name.Bound(addr, id, residual))))
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
      InstrumentedBind(instrumentedObsv, pollState)
    }

  def apply(servicePath: Path): Option[Activity[NameTree[Name]]] = {
    val bound =
      Option(lookupStatus.get(servicePath)) orElse
        pathParser(servicePath).map {
          case (dc, key, id, residual) =>
            log.debug("consul lookup: %s %s", dc, id.show)
            lookupCounter.incr()
            var binding: InstrumentedBind = null
            lookupStatusMu.synchronized {
              binding = Option(lookupStatus.get(servicePath)).getOrElse(lookup(dc, key, id, residual))
              lookupStatus.put(servicePath, binding)
            }
            binding
        }
    bound.map(_.act.underlying)
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

/* collects the binding metrics to expose */
private[consul] case class InstrumentedBind(
  act: InstrumentedActivity[NameTree[Name.Bound]],
  poll: PollState[http.Request, v1.IndexedServiceNodes]
)
