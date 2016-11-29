package io.buoyant.namerd.iface

import com.twitter.finagle.{ServiceFactory, Stack}
import com.twitter.finagle.buoyant.TlsClientPrep

object TlsTransformer {
  object Identity extends Stack.Transformer {
    def apply[Req, Rep](stack: Stack[ServiceFactory[Req, Rep]]): Stack[ServiceFactory[Req, Rep]] = stack
  }

  def apply(config: Option[ClientTlsConfig]): Stack.Transformer = config match {
    case None => Identity
    case Some(tls) => new TlsTransformer(tls.commonName, tls.caCert)
  }

  def prepOnly(config: Option[ClientTlsConfig]): Stack.Transformer = config match {
    case None => Identity
    case Some(tls) => new Stack.Transformer {
      private[this] def prep[Req, Rep] = TlsClientPrep.static[Req, Rep](tls.commonName, tls.caCert)
      override def apply[Req, Rep](underlying: Stack[ServiceFactory[Req, Rep]]) =
        prep[Req, Rep] +: underlying
    }
  }
}

class TlsTransformer(cn: String, cert: Option[String]) extends Stack.Transformer {
  private[this] def prep[Req, Rep] = TlsClientPrep.static[Req, Rep](cn, cert)
  private[this] def config[Req, Rep] = TlsClientPrep.configureFinagleTls[Req, Rep]

  override def apply[Req, Rep](underlying: Stack[ServiceFactory[Req, Rep]]) =
    prep[Req, Rep] +: config[Req, Rep] +: underlying
}
