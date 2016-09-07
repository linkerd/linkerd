package io.buoyant.namer.consul

import com.twitter.finagle.http.{Response, Status}
import com.twitter.finagle.{Addr, Failure}
import com.twitter.util._
import io.buoyant.consul.v1

private[consul] object DcServices {

  /**
   * Contains all cached serviceMap responses and the mapping of names
   * to Addrs for a particular datacenter.
   */
  def apply(
    consulApi: v1.ConsulApi,
    name: String,
    domain: Option[String]
  ): Activity[Map[SvcKey, Var[Addr]]] = {

    def getServices(index: Option[String]): Future[v1.Indexed[Set[SvcKey]]] =
      consulApi.serviceMap(
        datacenter = Some(name),
        blockingIndex = index,
        retry = true
      ).map(toServices)

    val states = Var.async[Activity.State[Map[SvcKey, Var[Addr]]]](Activity.Pending) { state =>
      @volatile var stopped: Boolean = false

      def loop(index0: Option[String], cache: Map[SvcKey, Var[Addr]]): Future[Unit] =
        if (stopped) Future.Unit
        else getServices(index0).transform {
          case Throw(e@v1.UnexpectedResponse(rsp)) if rsp.status == Status.NotFound =>
            // We assume this means the datacenter doesn't exist, so
            // we just treat it as empty.  This will never be updated
            // though.  If we care about this, we should schedule
            // recovery with e.g.:
            //   Future.sleep(t).before(loop(index0))
            state() = Activity.Ok(Map.empty)
            Future.exception(e)

          case Throw(e) =>
            state() = Activity.Failed(e)
            Future.exception(e)

          case Return(v1.Indexed(_, None)) =>
            val e = NoIndexException
            state() = Activity.Failed(e)
            Future.exception(e)

          case Return(v1.Indexed(keys, index1)) =>
            cache.keys.foreach { k =>
              if (!keys(k)) log.debug("consul deleted: %s", k)
            }

            val updated = keys.map { k =>
              val svc = cache.get(k) match {
                case Some(svc) => svc
                case None =>
                  log.debug("consul added: %s", k)
                  SvcAddr(consulApi, name, k, domain)
              }
              k -> svc
            }.toMap

            state() = Activity.Ok(updated)
            loop(index1, updated)
        }

      val pending = loop(None, Map.empty)
      Closable.make { _ =>
        stopped = true
        pending.raise(DcRelease)
        Future.Unit
      }
    }

    Activity(states)
  }

  private[this] val NoIndexException =
    Failure("consul did not return an index")

  private[this] val DcRelease =
    Failure("dc observation released").flagged(Failure.Interrupted)

  private[this] val toServices: v1.Indexed[Map[String, Seq[String]]] => v1.Indexed[Set[SvcKey]] = {
    case v1.Indexed(services, idx) =>
      val keys = services.flatMap {
        case (svcName, tags) =>
          tags.map(tag => SvcKey(svcName, Some(tag))) :+ SvcKey(svcName, None)
      }
      v1.Indexed(keys.toSet, idx)
  }

}
