package io.buoyant.linkerd.clientTls

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.TlsClientPrep
import io.buoyant.config.Parser
import io.buoyant.linkerd.{TlsClientConfig, TlsClientInitializer}

class StaticInitializer extends TlsClientInitializer {
  val configClass = classOf[StaticConfig]
  override def configId = "io.l5d.static"
}

object StaticInitializer extends StaticInitializer

case class StaticConfig(commonName: String, caCertPath: Option[String]) extends TlsClientConfig {
  @JsonIgnore
  override def tlsClientPrep[Req, Rsp] =
    TlsClientPrep.static[Req, Rsp](commonName, caCertPath)
}
