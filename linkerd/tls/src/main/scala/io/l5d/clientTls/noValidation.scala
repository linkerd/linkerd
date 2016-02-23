package io.l5d.clientTls

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.buoyant.TlsClientPrep.Module
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.{TlsClientConfig, TlsClientInitializer}

class noValidation extends TlsClientInitializer {
  val configClass = classOf[NoValidationConfig]
  val configId = "io.l5d.clientTls.noValidation"
}

class NoValidationConfig extends TlsClientConfig {
  @JsonIgnore
  override def tlsClientPrep[Req, Rsp]: Module[Req, Rsp] =
    TlsClientPrep.withoutCertificateValidation[Req, Rsp]
}
