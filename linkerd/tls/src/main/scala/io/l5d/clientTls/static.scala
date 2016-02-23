package io.l5d.clientTls

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.buoyant.TlsClientPrep.Module
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.{TlsClientConfig, TlsClientInitializer}

class static extends TlsClientInitializer {
  val configClass = classOf[StaticConfig]
  val configId = "io.l5d.clientTls.static"
}

case class StaticConfig(commonName: String, caCertPath: Option[String]) extends TlsClientConfig {
  @JsonIgnore
  override def tlsClientPrep[Req, Rsp]: Module[Req, Rsp] =
    TlsClientPrep.static[Req, Rsp](
      commonName,
      caCertPath
    )
}
