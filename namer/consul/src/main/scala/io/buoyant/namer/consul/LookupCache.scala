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
    watchDc(dcName).map { services =>
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

  private[this] lazy val domain: Activity[Option[String]] =
    if (setHost) {
      Activity.future(agentApi.localAgent(retry = true)).map { la =>
        val dom = la.Config.flatMap(_.Domain).getOrElse("consul")
        Some(dom.stripPrefix(".").stripSuffix("."))
      }
    } else Activity.value(None)

  protected[this] def watchDc(dc: String): Activity[Map[SvcKey, Var[Addr]]] =
    domain.flatMap { domain =>
      Activity(Dc.get(dc, domain).services).map(_.mapValues(_.addrs))
    }

  /**
   * Contains all cached responses from the Consul API
   */
  private[this] object Dc {
    private[this] val activity: ActUp[Map[String, DcCache]] =
      Var(Activity.Pending)

    /**
     * Returns existing datacenter cache with that name
     * or creates a new one
     */
    def get(name: String, domain: Option[String]): DcCache =
      synchronized {
        activity.sample() match {
          case Activity.Ok(snap) => snap.getOrElse(name, mkAndUpdate(snap, name, domain))
          case _ => mkAndUpdate(Map.empty, name, domain)
        }
      }

    private[this] def mkAndUpdate(
      cache: Map[String, DcCache],
      name: String,
      domain: Option[String]
    ): DcCache = {
      val dc = new DcCache(consulApi, name, domain)
      activity() = Activity.Ok(cache + (name -> dc))
      dc
    }
  }
}
