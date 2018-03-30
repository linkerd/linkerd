package com.twitter.finagle.buoyant

import com.twitter.finagle.Path
import scala.annotation.tailrec

/**
 * A PathMatcher extracts values for variables from a Path and can substitute
 * those values to build a new String.
 */
trait PathMatcher {

  /**
   * Extracts a map of variable name to variable value from the given Path.
   * Returns None if the Path isn't matched by this PathMatcher.
   */
  def extract(path: Path): Option[Map[String, String]]

  /**
   * Extract variable values from the Path and substitute them into the
   * pattern.
   */
  def substitute(path: Path, pattern: String): Option[String] =
    extract(path).map(PathMatcher.substitute(_, pattern))

  /**
   * Substitute the given variable values into the pattern and build a Path.
   */
  def substitutePath(vars: Map[String, String], pattern: String): Path =
    Path.read(PathMatcher.substitute(vars, pattern))

  /**
   * Extract variable values from the Path, substitute them into the
   * pattern, and build a Path.
   */
  def substitutePath(path: Path, pattern: String): Option[Path] =
    extract(path).map(substitutePath(_, pattern))
}

object PathMatcher {

  /**
   * Create a PathMatcher.  expr must be a `'/'` delimited path where each
   * segment is either a legal Path segment or:
   * - the wildcard string `"*"` which matches any single segment
   * - a variable capture string of the form `"{foo}"` which captures the value
   *   of the matched segment into the variable named foo.
   *
   * Captured variables can be used in substitution patterns: occurrences of
   * `"{foo}"` will be replaced with foo's value.
   */
  def apply(expr: String): PathMatcher = new Matcher(expr)

  def substitute(vars: Map[String, String], pattern: String): String =
    vars.foldRight(pattern) {
      case ((k, v), pat) =>
        pat.replace(s"{$k}", v)
    }

  private class Matcher(expr: String) extends PathMatcher {

    val exprSegments = expr.split("/").dropWhile(_.isEmpty)

    override def extract(path: Path): Option[Map[String, String]] =
      _extract(path.showElems, exprSegments, Map.empty)

    @tailrec
    private[this] def _extract(
      pathSegments: Seq[String],
      exprSegments: Seq[String],
      vars: Map[String, String]
    ): Option[Map[String, String]] =
      (pathSegments.headOption, exprSegments.headOption) match {
        case (Some(pathSegment), Some(exprSegment)) =>
          if (exprSegment == "*")
            _extract(pathSegments.tail, exprSegments.tail, vars)
          else if (exprSegment == pathSegment)
            _extract(pathSegments.tail, exprSegments.tail, vars)
          else
            """\{([a-zA-Z0-9.:-]+)\}""".r.findAllIn(exprSegment) match {
              case k if !k.isEmpty && pathSegment.startsWith(exprSegment.take(k.start)) =>
                var keys = k.toArray
                keys.foldLeft(exprSegment.replace(".", "\\."))(_re).r.findAllIn(pathSegment) match {
                  case v if !v.isEmpty =>
                    _extract(
                      pathSegments.tail,
                      exprSegments.tail,
                      vars ++ keys.map { key =>
                        key.drop(1).dropRight(1) -> v.group(keys.indexOf(key) + 1)
                      }.toMap
                    )
                  case _ => None
                }
              case _ => None
            }
        case (_, None) => Some(vars)
        case (None, _) => None
      }

    private[this] def _re(x: String, y: String) = x.replace(y, "(.*)")

    override def toString: String = expr
  }
}
