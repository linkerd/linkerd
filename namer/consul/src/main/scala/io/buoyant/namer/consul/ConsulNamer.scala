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
    val taggingParser = (path: Path) =>
      path.take(3) match {
        case id@Path.Utf8(dc, tag, service) =>
          val k = SvcKey(service.toLowerCase, Some(tag.toLowerCase))
          Some((dc, k, prefix ++ id, path.drop(3)))
        case _ => None
      }

    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, preferServiceAddress, weights, stats, taggingParser)
    new NamerWithHandlers(lookup, prefix)
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
    val untaggingParser = (path: Path) =>
      path.take(2) match {
        case id@Path.Utf8(dc, service) =>
          val k = SvcKey(service.toLowerCase, None)
          Some((dc, k, prefix ++ id, path.drop(2)))
        case _ => None
      }

    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, preferServiceAddress, weights, stats, untaggingParser)
    new NamerWithHandlers(lookup, prefix)
  }

  private[this] class NamerWithHandlers(protected val cache: LookupCache, protected val prefix: Path)
    extends Namer
    with Admin.WithHandlers {

    def lookup(path: Path): Activity[NameTree[Name]] =
      cache(path).getOrElse(Activity.value(NameTree.Neg))

    val handlerPrefix = prefix.drop(1).show.drop(1) // drop leading "/#/"
    override def adminHandlers: Seq[Admin.Handler] = Seq(
      Admin.Handler(
        s"/namer_state/${handlerPrefix}.json",
        new ConsulNamerHandler()
      )
    )
  }

  class ConsulNamerHandler() extends Service[Request, Response] {
    override def apply(request: Request): Future[Response] = ???
  }

}
