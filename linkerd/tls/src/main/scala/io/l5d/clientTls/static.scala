package io.l5d.clientTls

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.buoyant.TlsClientPrep.Module
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.{TlsClientConfig, TlsClientInitializer}

class StaticInitializer extends TlsClientInitializer {
  val configClass = classOf[static]
}

object StaticInitializer extends StaticInitializer

case class static(commonName: String, caCertPath: Option[String]) extends TlsClientConfig {
  @JsonIgnore
  override def tlsClientPrep[Req, Rsp]: Module[Req, Rsp] =
    TlsClientPrep.static[Req, Rsp](
      commonName,
      caCertPath
    )
}
