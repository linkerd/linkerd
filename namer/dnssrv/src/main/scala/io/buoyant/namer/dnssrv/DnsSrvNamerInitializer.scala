package io.buoyant.namer.dnssrv

import java.util.Collections

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.Stack.Params
import com.twitter.finagle._
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import org.xbill.DNS
import DnsSrvNamerConfig.Edns

class DnsSrvNamerInitializer extends NamerInitializer {
  override val configClass = classOf[DnsSrvNamerConfig]
  override def configId: String = "io.l5d.dnssrv"
}

object DnsSrvNamerInitializer extends DnsSrvNamerInitializer

case class DnsSrvNamerConfig(
  refreshIntervalSeconds: Option[Int],
  dnsHosts: Option[Seq[String]]
) extends NamerConfig {

  @JsonIgnore
  override def experimentalRequired = true

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.dnssrv")

  @JsonIgnore
  override def newNamer(params: Params): Namer = {

    val stats = params[param.Stats].statsReceiver.scope(prefix.show.stripPrefix("/"))
    def resolver: DNS.Resolver = dnsHosts match {
      case Some(hosts) => new DNS.ExtendedResolver(hosts.toArray)
      case None => new DNS.ExtendedResolver()
    }
    resolver.setEDNS(
      Edns.Level,
      Edns.MaxPayloadSize,
      Edns.Flags,
      Edns.Options
    )
    val timer = params[param.Timer].timer
    val refreshInterval = refreshIntervalSeconds.getOrElse(5).seconds
    new DnsSrvNamer(prefix, () => resolver, refreshInterval, stats)(timer)
  }
}

object DnsSrvNamerConfig {

  object Edns {
    val Level = 0
    val MaxPayloadSize = 2048
    val Flags = 0
    val Options = Collections.EMPTY_LIST
  }

}
