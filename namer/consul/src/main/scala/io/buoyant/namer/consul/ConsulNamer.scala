package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.consul.v1
import io.buoyant.namer.InstrumentedActivity
import scala.collection.mutable

object ConsulNamer {

  def tagged(
    prefix: Path,
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false,
    consistency: Option[v1.ConsistencyMode] = None,
    preferServiceAddress: Option[Boolean] = None,
    weights: Map[String, Double] = Map.empty,
    stats: StatsReceiver = NullStatsReceiver
  ): Namer = {
    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, preferServiceAddress, weights, stats)
    new TaggedNamer(lookup, prefix)
  }

  def untagged(
    prefix: Path,
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false,
    consistency: Option[v1.ConsistencyMode] = None,
    preferServiceAddress: Option[Boolean] = None,
    weights: Map[String, Double] = Map.empty,
    stats: StatsReceiver = NullStatsReceiver
  ): Namer = {
    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, preferServiceAddress, weights, stats)
    new UntaggedNamer(lookup, prefix)
  }

  private[this] trait NamerWithHandlers extends Namer with Admin.WithHandlers {
    protected def prefix: Path
    val handlerPrefix = prefix.drop(1).show.drop(1) // drop leading "/#/"
    override def adminHandlers: Seq[Admin.Handler] = Seq(
      Admin.Handler(
        s"/namer_state/${handlerPrefix}.json",
        new ConsulNamerHandler()
      )
    )
  }

  private[this] class TaggedNamer(protected val lookup: LookupCache, protected val prefix: Path)
    extends NamerWithHandlers {

    //use a mutable and avoid mutex to get a faster response, could lead to any catastrofic failure scenario?
    private[this] val lookupStatus = mutable.Map.empty[Path, InstrumentedBind]

    def lookup(path: Path): Activity[NameTree[Name]] =
      path.take(3) match {
        case id@Path.Utf8(dc, tag, service) =>
          val k = SvcKey(service.toLowerCase, Some(tag.toLowerCase))
          val bind = lookup(dc, k, prefix ++ id, path.drop(3))
          lookupStatus(path) = bind
          bind.act.underlying

        case _ => Activity.value(NameTree.Neg)
      }
  }

  private[this] class UntaggedNamer(protected val lookup: LookupCache, protected val prefix: Path)
    extends NamerWithHandlers {

    //use a mutable and avoid mutex to get a faster response, could lead to any catastrofic failure scenario?
    private[this] val lookupStatus = mutable.Map.empty[Path, InstrumentedBind]

    def lookup(path: Path): Activity[NameTree[Name]] =
      path.take(2) match {
        case id@Path.Utf8(dc, service) =>
          val k = SvcKey(service.toLowerCase, None)
          val bind = lookup(dc, k, prefix ++ id, path.drop(2))
          lookupStatus(path) = bind
          bind.act.underlying

        case _ => Activity.value(NameTree.Neg)
      }
  }

  class ConsulNamerHandler() extends Service[Request, Response] {
    override def apply(request: Request): Future[Response] = ???
  }

}
