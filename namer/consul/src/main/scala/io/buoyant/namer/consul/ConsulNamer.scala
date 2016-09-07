package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.consul._
import io.buoyant.consul.v1.{ServiceNode, UnexpectedResponse}
import io.buoyant.namer.Metadata

class ConsulNamer(
  idPrefix: Path,
  consulApi: v1.ConsulApi,
  agentApi: v1.AgentApi,
  includeTag: Boolean = false,
  setHost: Boolean = false
) extends Namer {

  /**
   * Accepts names in the form:
   * /<datacenter>/<svc-name>/residual/path
   * or, if `includeTag` is true, in the form:
   * /<datacenter>/<tag>/<svc-name>/residual/path
   */
  def lookup(path: Path): Activity[NameTree[Name]] =
    path match {
      case Path.Utf8(dcName, serviceName, residual@_*) if !includeTag =>
        lookup(
          dcName,
          SvcKey(serviceName, None),
          idPrefix ++ Path.Utf8(dcName, serviceName),
          Path.Utf8(residual: _*)
        )
      case Path.Utf8(dcName, tag, serviceName, residual@_*) if includeTag =>
        lookup(
          dcName,
          SvcKey(serviceName, Some(tag)),
          idPrefix ++ Path.Utf8(dcName, tag, serviceName),
          Path.Utf8(residual: _*)
        )
      case _ =>
        Activity.value(NameTree.Neg)
    }

  def lookup(
    dcName: String,
    svcKey: SvcKey,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = {
    log.debug("consul lookup: %s %s", id.show)
    domain.flatMap { domain =>
      Activity(Dc.get(dcName, domain).services).map { services =>
        log.debug("consul dc %s initial state: %s", dcName, services.keys.mkString(", "))
        services.get(svcKey) match {
          case None =>
            log.debug("consul dc %s service %s missing", dcName, svcKey)
            NameTree.Neg

          case Some(service) =>
            log.debug("consul ns %s service %s found + %s", dcName, svcKey, residual.show)
            NameTree.Leaf(Name.Bound(service.addrs, id, residual))
        }
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
