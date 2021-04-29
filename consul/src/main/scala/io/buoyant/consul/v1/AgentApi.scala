package io.buoyant.consul.v1

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Backoff, http}
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.util.{Closable, Future}

object AgentApi {
  def apply(c: Client): AgentApi = new AgentApi(c, s"/$versionString")
}

class AgentApi(
  val client: Client,
  val uriPrefix: String,
  val backoffs: Backoff = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
  val stats: StatsReceiver = DefaultStatsReceiver
) extends BaseApi with Closable {

  val agentPrefix = s"$uriPrefix/agent"

  // https://www.consul.io/docs/agent/http/agent.html#agent_self
  def localAgent(retry: Boolean = false): Future[LocalAgent] = {
    val req = mkreq(http.Method.Get, s"$agentPrefix/self", None)
    executeJson[LocalAgent](req, retry).map(_.value)
  }
}

// Classes below are used for JSON parsing hence field names case have to match the on in JSON

// Represents configuration and member information of the local agent
// For now only Config is parsed as other fields are unused
case class LocalAgent(
  Config: Option[Config] // configuration of the local agent stored under the Config key
)

// Represents configuration of the local agent
case class Config(
  Domain: Option[String], // TLD for DNS zone handled by consul, e.g. consul.acme.co.
  Datacenter: Option[String] // Name of agent's datacenter
)
