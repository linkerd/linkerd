package io.l5d.clientTls

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.buoyant.TlsClientPrep.Module
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.{TlsClientConfig, TlsClientInitializer}

class NoValidationInitializer extends TlsClientInitializer {
  val configClass = classOf[noValidation]
}

object NoValidationInitializer extends NoValidationInitializer

class noValidation extends TlsClientConfig {
  @JsonIgnore
  override def tlsClientPrep[Req, Rsp]: Module[Req, Rsp] =
    TlsClientPrep.withoutCertificateValidation[Req, Rsp]
}
