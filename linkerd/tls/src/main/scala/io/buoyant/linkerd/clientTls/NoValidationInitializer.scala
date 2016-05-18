package io.buoyant.linkerd.clientTls

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.buoyant.TlsClientPrep.Module
import io.buoyant.linkerd.{TlsClientConfig, TlsClientInitializer}

class NoValidationInitializer extends TlsClientInitializer {
  val configClass = classOf[NoValidationConfig]
  override val configId = "io.l5d.noValidation"
}

object NoValidationInitializer extends NoValidationInitializer

class NoValidationConfig extends TlsClientConfig {
  @JsonIgnore
  override def tlsClientPrep[Req, Rsp]: Module[Req, Rsp] =
    TlsClientPrep.withoutCertificateValidation[Req, Rsp]
}
