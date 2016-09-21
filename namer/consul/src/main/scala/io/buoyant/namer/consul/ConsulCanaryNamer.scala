package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util._
import io.buoyant.consul.v1

object ConsulCanaryNamer {

  def tagged(
    prefix: Path,
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false,
    consistency: Option[v1.ConsistencyMode] = None,
    stats: StatsReceiver = NullStatsReceiver
  ): Namer = {
    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, stats)
    new TaggedNamer(lookup, prefix)
  }

  private[this] class TaggedNamer(lookup: LookupCache, prefix: Path) extends Namer {

    def lookup(path: Path): Activity[NameTree[Name]] = {
      path.take(3) match {
        case id@Path.Utf8(dc, tag, service) =>
          val k = SvcKey(service, Option(tag))
          val result = lookup(dc, k, prefix ++ id, path.drop(3))
          result
        case _ => {
          Activity.value(NameTree.Neg)
        }
      }
    }
  }

}
