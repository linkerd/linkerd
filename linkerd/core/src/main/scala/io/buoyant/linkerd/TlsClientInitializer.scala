package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.buoyant.TlsClientPrep
import io.buoyant.config.ConfigInitializer

/**
 * Loadable TLS client configuration module.
 *
 * Implementers may read params from the config file and must produce a
 * TlsClientPrep module which will control how this router makes TLS requests.
 */
abstract class TlsClientInitializer extends ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait TlsClientConfig {
  @JsonIgnore
  def tlsClientPrep[Req, Rsp]: TlsClientPrep.Module[Req, Rsp]
}
