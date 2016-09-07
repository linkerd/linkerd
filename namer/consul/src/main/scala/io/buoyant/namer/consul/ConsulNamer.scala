package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.consul._
import io.buoyant.consul.v1.{ServiceNode, UnexpectedResponse}
import io.buoyant.namer.Metadata

object ConsulNamer {

  def tagged(
    prefix: Path,
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false
  ): Namer = new Tagged(Base(consulApi, agentApi, setHost), prefix)

  def untagged(
    prefix: Path,
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false
  ): Namer = new Untagged(Base(consulApi, agentApi, setHost), prefix)

  private[this] class Tagged(base: Base, prefix: Path) extends Namer {

    def lookup(path: Path): Activity[NameTree[Name]] =
      path.take(3) match {
        case id@Path.Utf8(dc, tag, service) =>
          val k = SvcKey(service, Some(tag))
          base.lookup(dc, k, prefix ++ id, path.drop(3))

        case _ => Activity.value(NameTree.Neg)
      }
  }

  private[this] class Untagged(base: Base, prefix: Path) extends Namer {

    def lookup(path: Path): Activity[NameTree[Name]] =
      path.take(2) match {
        case id@Path.Utf8(dc, service) =>
          val k = SvcKey(service, None)
          base.lookup(dc, k, prefix ++ id, path.drop(2))

        case _ => Activity.value(NameTree.Neg)
      }
  }

  private[this] case class Base(
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false
  ) {

    def lookup(
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

}
