package io.buoyant.linkerd

import com.fasterxml.jackson.core.{JsonParser, JsonToken, TreeNode}
import com.twitter.finagle.{Namer, Path, Stack}

/**
 * Loadable namer configuration module.
 *
 * Implementers may provide default configurations on the initial `params` value.
 */
abstract class NamerInitializer {
  /** Configuration state. */
  def params: Stack.Params

  /** Create a new NamerInitializer with a new configuration */
  def withParams(ps: Stack.Params): NamerInitializer

  /** Append the given param to the configuration */
  def configured[P: Stack.Param](p: P): NamerInitializer = withParams(params + p)

  def prefix: Path = params[NamerInitializer.Prefix].path

  /** List of param names that are readable by this namer. */
  def paramKeys: Set[String]

  /**
   * Read the value for parameter named `key`.  An error is thrown if
   * `key` is not in `paramKeys`.
   */
  def readParam(key: String, p: JsonParser): NamerInitializer

  /**
   * Construct a namer.
   */
  def newNamer(): Namer
}

object NamerInitializer {

  /**
   * A configuration parameter that indicates the prefix of names that
   * should be refined through a given namer.  For example, if a
   * NamerInitializer is configured with the prefix `/pfx`, then a
   * name like `/pfx/mule/variations` would cause the name
   * `/mule/variations` to be resolved through the resulting Namer.
   */
  case class Prefix(path: Path)
  implicit object Prefix extends Stack.Param[Prefix] {
    val default = Prefix(Path.empty)
  }

  /**
   * Read a single namer configuration in the form:
   *
   * <pre>
   *   kind: io.l5d.izzle
   *   prefix: /i
   *   frizzle: dee
   *   swizzle: dah
   * </pre>
   *
   * In this example _io.l5d.izzle_ must be the _kind_ of a
   * [[NamerInitializer]] in `namers`.  _frizzle_ and _swizzle_ are
   * namer-specific options.  This namer refines names beginning with
   * `/i` (after this prefix has been stripped).
   */
  private[linkerd] def read(getNamer: String => Option[NamerInitializer], p: JsonParser): NamerInitializer = {
    val obj = Parsing.ensureTok(p, JsonToken.START_OBJECT) { p =>
      p.readValueAsTree(): TreeNode
    }
    p.nextToken()

    val namer: NamerInitializer = {
      val t = obj.traverse()
      t.setCodec(p.getCodec)
      t.nextToken()
      t.overrideCurrentName("namer")
      Parsing.findInObject(t) {
        case ("kind", p) =>
          Parsing.ensureTok(p, JsonToken.VALUE_STRING) { p =>
            val kind = p.getText()
            p.nextToken()
            getNamer(kind) match {
              case None => throw Parsing.error(s"unknown namer kind: '$kind'", p)
              case Some(namer) => namer
            }
          }
      } match {
        case None => throw Parsing.error(s"namer requires a 'kind' attribute", p)
        case Some(namer) => namer
      }
    }

    val t = obj.traverse()
    t.setCodec(p.getCodec)
    t.nextToken()
    t.overrideCurrentName("namer")
    Parsing.foldObject(t, namer) {
      case (namer, "kind", p) =>
        Parsing.skipValue(p)
        namer

      case (namer, key, p) if Params.keys(key) =>
        namer.withParams(Params.read(key, p, namer.params))

      case (namer, key, p) if namer.paramKeys(key) =>
        namer.readParam(key, p)

      case (_, key, p) =>
        throw Parsing.error(s"unexpected namer attribute: '$key'", p)
    }
  }

  /**
   * Parses params that apply to all namers.
   */
  object Params extends Parsing.Params {
    val Prefix = Parsing.Param.Text("prefix") { prefix =>
      val path = Path.read(prefix)
      if (path.isEmpty) throw new IllegalArgumentException("namer prefix must not be empty")
      else NamerInitializer.Prefix(path)
    }

    private[this] val underlying: Parsing.Params = Prefix
    def keys = underlying.keys
    def read(k: String, p: JsonParser, ps: Stack.Params) = underlying.read(k, p, ps)
  }
}
