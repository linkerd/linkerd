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
      System.err.format("ConsulCanaryNamer: got path: %s\n\n", path.toString())
      path.take(4) match {
        case id@Path.Utf8(dc, tag, canary, service) =>
          val canarytag: Option[String] =
            canary match {
              case "enabled" if tag == "canary" =>
                Option("canary")
              case "disabled" =>
                None
              case "always" =>
                Option("canary")
              case _ =>
                None
            }
          val k = SvcKey(service, canarytag)
          val result = lookup(dc, k, prefix ++ id, path.drop(4))
          System.err.format("ConsulCanaryNamer: lookup result: %s\n\n", result.toString)
          result
        case _ => {
          System.err.format("ConsulCanaryNamer: Negative lookup result!\n\n")
          Activity.value(NameTree.Neg)
        }
      }
    }
  }

}
