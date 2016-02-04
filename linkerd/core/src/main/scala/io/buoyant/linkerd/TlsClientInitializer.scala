package io.buoyant.linkerd

import com.fasterxml.jackson.core.{JsonParser, JsonToken, TreeNode}
import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.TlsClientPrep

/**
 * Loadable TLS client configuration module.
 *
 * Implementers may read params from the config file and must produce a
 * TlsClientPrep module which will control how this router makes TLS requests.
 */
trait TlsClientInitializer {

  /** Configuration state. */
  def params: Stack.Params

  /** Create a new TlsClientInitializer with a new configuration */
  def withParams(ps: Stack.Params): TlsClientInitializer

  /** Append the given param to the configuration */
  def configured[P: Stack.Param](p: P): TlsClientInitializer = withParams(params + p)

  /** List of param names that are readable by this namer. */
  def paramKeys: Set[String]

  /**
   * Read the value for parameter named `key`.  An error is thrown if
   * `key` is not in `paramKeys`.
   */
  def readParam(key: String, p: JsonParser): TlsClientInitializer

  /** The TslClientPrep module that will be used to make TLS requests */
  def tlsClientPrep[Req, Rsp]: TlsClientPrep.Module[Req, Rsp]
}

object TlsClientInitializer {

  /**
   * Read a single TlsClientInitializer configuration in the form:
   *
   * <pre>
   *   kind: io.l5d.FooTlsClient
   *   foo: bar
   * </pre>
   */
  private[linkerd] def read(getTls: String => Option[TlsClientInitializer], p: JsonParser): TlsClientInitializer = {
    val obj = Parsing.ensureTok(p, JsonToken.START_OBJECT) { p =>
      p.readValueAsTree(): TreeNode
    }
    p.nextToken()

    val tlsClientInitializer: TlsClientInitializer = {
      val t = obj.traverse()
      t.setCodec(p.getCodec)
      t.nextToken()
      t.overrideCurrentName("tls")
      Parsing.findInObject(t) {
        case ("kind", p) =>
          Parsing.ensureTok(p, JsonToken.VALUE_STRING) { p =>
            val kind = p.getText()
            p.nextToken()
            getTls(kind) match {
              case None => throw Parsing.error(s"unknown tls kind: '$kind'", p)
              case Some(tls) => tls
            }
          }
      } match {
        case None => throw Parsing.error(s"tls requires a 'kind' attribute", p)
        case Some(tls) => tls
      }
    }

    val t = obj.traverse()
    t.setCodec(p.getCodec)
    t.nextToken()
    t.overrideCurrentName("tls")
    Parsing.foldObject(t, tlsClientInitializer) {
      case (tls, "kind", p) =>
        Parsing.skipValue(p)
        tls

      case (tls, key, p) if tls.paramKeys(key) =>
        tls.readParam(key, p)

      case (_, key, p) =>
        throw Parsing.error(s"unexpected tls attribute: '$key'", p)
    }
  }
}
