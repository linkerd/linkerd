package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{ServiceFactory, Stackable}
import com.twitter.finagle.buoyant.TlsClientPrep
import io.buoyant.config.{PolymorphicConfig, ConfigInitializer}

/**
 * Loadable TLS client configuration module.
 *
 * Implementers may read params from the config file and must produce a
 * TlsClientPrep module which will control how this router makes TLS requests.
 */
abstract class TlsClientInitializer extends ConfigInitializer

abstract class TlsClientConfig extends PolymorphicConfig {
  @JsonIgnore
  def tlsClientPrep[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]]
}
