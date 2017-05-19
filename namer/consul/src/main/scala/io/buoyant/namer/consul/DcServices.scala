package io.buoyant.namer.consul

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Addr, ConnectionFailedException, Failure}
import com.twitter.util._
import io.buoyant.consul.v1

private[consul] object DcServices {

  /**
   * We use a shared stats object so that counters/stats are not
   * created anew for each DC/service.
   */
  case class Stats(stats0: StatsReceiver) {
    val stats = stats0.scope("dc")

    val opens = stats.counter("opens")
    val closes = stats.counter("closes")
    val errors = stats.counter("errors")
    val updates = stats.counter("updates")
    val adds = stats.counter("adds")
    val removes = stats.counter("removes")

    val service = SvcAddr.Stats(stats0.scope("service"))
  }

  /**
   * Contains all cached serviceMap responses and the mapping of names
   * to Addrs for a particular datacenter.
   *
   * If the named datacenter does not exist, the consul API will retry
   * indefinitely.  This is because missing datacenters cannot be
   * distinguished from server errors.
   */
  def apply(
    consulApi: v1.ConsulApi,
    name: String,
    domain: Option[String],
    consistency: Option[v1.ConsistencyMode],
    preferServiceAddress: Option[Boolean] = None,
    stats: Stats
  ): Activity[Map[SvcKey, Var[Addr]]] = {

    def getServices(index: Option[String]): Future[v1.Indexed[Set[SvcKey]]] =
      consulApi.serviceMap(
        datacenter = Some(name),
        blockingIndex = index,
        consistency = consistency,
        retry = true
      ).map(toServices)

    val states = Var.async[Activity.State[Map[SvcKey, Var[Addr]]]](Activity.Pending) { state =>
      stats.opens.incr()

      @volatile var stopped: Boolean = false
      def loop(index0: Option[String], cache: Map[SvcKey, Var[Addr]]): Future[Unit] = {
        if (stopped) Future.Unit
        else getServices(index0).transform {
          case Throw(Failure(Some(err: ConnectionFailedException))) =>
            // Drop the index, in case it's been reset by a consul restart
            loop(None, cache)
          case Throw(e) =>
            // If an exception escaped getService's retries, we treat it as
            // effectively fatal to DC observation. In the future, we
            // may consider retrying certain failures (with backoff).
            state() = Activity.Failed(e)
            stats.errors.incr()
            Future.exception(e)

          case Return(v1.Indexed(_, None)) =>
            // If consul didn't give us an index, all bets are off.
            state() = Activity.Failed(NoIndexException)
            stats.errors.incr()
            Future.exception(NoIndexException)

          case Return(v1.Indexed(keys, index1)) =>
            stats.updates.incr()

            cache.keys.foreach { k =>
              if (!keys(k)) {
                log.debug("consul deleted: %s", k)
                stats.removes.incr()
              }
            }

            // Create a Var[Addr] for each new service. These addrs
            // are lazily evaluated, so no additional work is done
            // until the addr is observed.
            val updated = keys.map { k =>
              val svc = cache.get(k) match {
                case Some(svc) => svc
                case None =>
                  log.debug("consul added: %s", k)
                  stats.adds.incr()
                  SvcAddr(consulApi, name, k, domain, consistency, preferServiceAddress, stats.service)
              }
              k -> svc
            }.toMap

            state() = Activity.Ok(updated)
            loop(index1, updated)
        }
      }

      val pending = loop(None, Map.empty)
      Closable.make { _ =>
        stopped = true
        pending.raise(DcRelease)
        stats.closes.incr()
        Future.Unit
      }
    }

    Activity(states)
  }

  private[this] val NoIndexException =
    Failure("consul did not return an index")

  private[this] val DcRelease =
    Failure("dc observation released", Failure.Interrupted)

  private[this] val toServices: v1.Indexed[Map[String, Seq[String]]] => v1.Indexed[Set[SvcKey]] = {
    case v1.Indexed(services, idx) =>
      val keys = services.flatMap {
        case (svcName, tags) =>
          tags.map(tag => SvcKey(svcName.toLowerCase, Some(tag.toLowerCase))) :+ SvcKey(svcName.toLowerCase, None)
      }
      v1.Indexed(keys.toSet, idx)
  }

}
