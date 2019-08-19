package io.buoyant.namer.consul

import com.fasterxml.jackson.databind.SerializationFeature
import com.twitter.finagle._
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.consul.v1
import io.buoyant.namer.InstrumentedActivity

object ConsulNamer {

  def tagged(
    prefix: Path,
    consulApi: v1.ConsulApi,
    agentApi: v1.AgentApi,
    setHost: Boolean = false,
    consistency: Option[v1.ConsistencyMode] = None,
    preferServiceAddress: Option[Boolean] = None,
    weights: Map[String, Double] = Map.empty,
    stats: StatsReceiver = NullStatsReceiver,
    transferMetadata: Boolean = false
  ): Namer = {
    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, preferServiceAddress, weights, stats, transferMetadata)
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
    stats: StatsReceiver = NullStatsReceiver,
    transferMetadata: Boolean = false
  ): Namer = {
    val lookup = new LookupCache(consulApi, agentApi, setHost, consistency, preferServiceAddress, weights, stats, transferMetadata)
    new UntaggedNamer(lookup, prefix)
  }

  private[this] trait NamerWithHandlers
    extends Namer
    with Admin.WithHandlers {

    def prefix: Path

    def caching: LookupCache

    def parse(path: Path): Option[PathScheme]

    def lookup(path: Path): Activity[NameTree[Name]] =
      parse(path) match {
        case Some(PathScheme(dc, key, id, residual)) =>
          caching(path, dc, key, id, residual)
        case _ => Activity.value(NameTree.Neg)
      }

    //lazy to avoid initialization order issues
    lazy val handlerPrefix = prefix.drop(1).show.drop(1) // drop leading "/#/"

    override def adminHandlers: Seq[Admin.Handler] = Seq(
      Admin.Handler(
        s"/namer_state/${handlerPrefix}.json",
        new ConsulNamerHandler(caching.status)
      )
    )
  }

  private[this] class TaggedNamer(val caching: LookupCache, val prefix: Path) extends NamerWithHandlers {

    override def parse(path: Path): Option[PathScheme] =
      path.take(3) match {
        case id@Path.Utf8(dc, tag, service) =>
          val k = SvcKey(service.toLowerCase, Some(tag.toLowerCase))
          Some(PathScheme(dc, k, prefix ++ id, path.drop(3)))
        case _ => None
      }

  }

  private[this] class UntaggedNamer(val caching: LookupCache, val prefix: Path) extends NamerWithHandlers {

    override def parse(path: Path): Option[PathScheme] =
      path.take(2) match {
        case id@Path.Utf8(dc, service) =>
          val k = SvcKey(service.toLowerCase, None)
          Some(PathScheme(dc, k, prefix ++ id, path.drop(2)))
        case _ => None
      }

  }

  class ConsulNamerHandler(callStatus: => Map[Path, InstrumentedAddr])
    extends Service[Request, Response] {

    private[this] val mapper =
      Parser.jsonObjectMapper(Nil)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

    override def apply(request: Request): Future[Response] = {
      val state = callStatus.map {
        case (path, InstrumentedAddr(addr, poll)) =>
          path.show -> Map(
            "state" -> addr.stateSnapshot,
            "poll" -> poll
          )
      }

      val res = {
        val r = Response()
        r.mediaType = MediaType.Json
        r.contentString = mapper.writeValueAsString(state)
        r
      }

      Future.value(res)

    }
  }

}
