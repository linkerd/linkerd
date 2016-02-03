package io.l5d

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.twitter.finagle.{Path, Stack}
import io.buoyant.linkerd.{NamerInitializer, Parsing}
import io.buoyant.linkerd.util.PathMatcher

object rewrite {
  case class DstExpr(expr: String)
  implicit object DstExpr extends Stack.Param[DstExpr] {
    val default = DstExpr("")
    val parser = Parsing.Param.Text("dst")(DstExpr(_))
  }

  val parser = DstExpr.parser

  val defaultParams = Stack.Params.empty +
    NamerInitializer.Prefix(Path.Utf8("io.l5d.fs"))
}

/**
 * Initializes PathMatcher.Namer, supporting positional rewrite expressions.
 */
class rewrite(val params: Stack.Params) extends NamerInitializer {
  def this() = this(rewrite.defaultParams)
  def withParams(ps: Stack.Params) = new rewrite(ps)

  def paramKeys = rewrite.parser.keys
  def readParam(k: String, p: JsonParser) = withParams(rewrite.parser.read(k, p, params))

  def newNamer() = params[rewrite.DstExpr] match {
    case rewrite.DstExpr("") =>
      throw new IllegalArgumentException("io.l5d.rewrite requires a non-empty 'dst'")
    case rewrite.DstExpr(expr) if !expr.startsWith("/") =>
      throw new IllegalArgumentException("io.l5d.rewrite requires that 'dst' is a path starting with '/'")
    case rewrite.DstExpr(expr) => new PathMatcher.Namer(expr)
  }
}
