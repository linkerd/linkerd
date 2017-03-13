package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util._
import io.buoyant.consul.v1

object ConsulNamer {

  def tagged(
    prefix: Path,
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false,
    consistency: Option[v1.ConsistencyMode] = None,
    preferServiceAddress: Option[Boolean] = None,
    stats: StatsReceiver = NullStatsReceiver
  ): Namer = {
    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, preferServiceAddress, stats)
    new TaggedNamer(lookup, prefix)
  }

  def untagged(
    prefix: Path,
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false,
    consistency: Option[v1.ConsistencyMode] = None,
    preferServiceAddress: Option[Boolean] = None,
    stats: StatsReceiver = NullStatsReceiver
  ): Namer = {
    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, preferServiceAddress, stats)
    new UntaggedNamer(lookup, prefix)
  }

  private[this] class TaggedNamer(lookup: LookupCache, prefix: Path) extends Namer {

    def lookup(path: Path): Activity[NameTree[Name]] =
      path.take(3) match {
        case id@Path.Utf8(dc, tag, service) =>
          val k = SvcKey(service.toLowerCase, Some(tag.toLowerCase))
          lookup(dc, k, prefix ++ id, path.drop(3))

        case _ => Activity.value(NameTree.Neg)
      }
  }

  private[this] class UntaggedNamer(lookup: LookupCache, prefix: Path) extends Namer {

    def lookup(path: Path): Activity[NameTree[Name]] =
      path.take(2) match {
        case id@Path.Utf8(dc, service) =>
          val k = SvcKey(service.toLowerCase, None)
          lookup(dc, k, prefix ++ id, path.drop(2))

        case _ => Activity.value(NameTree.Neg)
      }
  }

}
