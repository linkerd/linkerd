package io.buoyant.linkerd

import com.fasterxml.jackson.core.{JsonToken, JsonParser}
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.util.LoadService

/**
 * A collection of TlsClientInitializer modules that are available to be used
 * to configure the way that TLS requests are sent.  Note that while several
 * TlsClientInitializer modules can be loaded, each router can only use at most
 * one of them.
 */
trait TlsClientInitializers {
  def kinds: Set[String]
  def get(k: String): Option[TlsClientInitializer]

  /**
   * Reads a tls section of a router config, finds the appropriate
   * TlsClientInitializer based on the kind field, configures the
   * TlsClientInitializer, and then produces a TlsClientPrep module.
   */
  def read[Req, Rsp](p: JsonParser): TlsClientPrep.Module[Req, Rsp]
}

object TlsClientInitializers {

  def apply(initializers: TlsClientInitializer*): TlsClientInitializers = {
    val byKind = initializers.groupBy(_.getClass.getName).map {
      case (kind, Seq(tci)) => kind -> tci
      case (kind, _) => throw new IllegalArgumentException(s"TlsClientInitializer kind conflict: '$kind'")
    }
    _TlsClientInitializers(byKind)
  }

  private case class _TlsClientInitializers(initializers: Map[String, TlsClientInitializer])
    extends TlsClientInitializers {

    def kinds = initializers.keySet
    def get(k: String) = initializers.get(k)

    def read[Req, Rsp](p: JsonParser): TlsClientPrep.Module[Req, Rsp] =
      Parsing.ensureTok(p, JsonToken.START_OBJECT) { p =>
        TlsClientInitializer.read(this.get, p).tlsClientPrep
      }
  }

  /**
   * Runtime-loaded TlsClientInitializer modules.
   *
   * Uses finagle's `LoadService` facility to discover protocol
   * support at runtime by searching the class path for
   * TlsClientInitializer subclasses.
   */
  def load(): TlsClientInitializers = {
    apply(LoadService[TlsClientInitializer]: _*)
  }

  def empty: TlsClientInitializers = _TlsClientInitializers(Map.empty)
}
