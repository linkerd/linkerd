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

case class MatchSegment(
  segment: String,
  regex: Option[scala.util.matching.Regex],
  captureKeys: Seq[String]
)

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

    override def extract(path: Path): Option[Map[String, String]] =
      _extract(path.showElems, rawSegments, Map.empty)

    private[this] val segmentRegex = """\{([a-zA-Z0-9\.:-_]+)\}""".r

    private[this] val rawSegments: Seq[MatchSegment] = {
      expr.split("/").dropWhile(_.isEmpty).map { exprSegment =>
        segmentRegex.findAllIn(exprSegment) match {
          case k if !k.isEmpty =>
            val keys = k.toArray
            // '.' is the only valid Path character that also has a regex meaning.  Any '.' characters must be escaped.
            val escaped = exprSegment.replace(".", "\\.")
            // Replace capture keys with regex captures.
            val withCaptures = keys.foldLeft(escaped)(_.replace(_, "(.*)")).r
            val keyNames = keys.map { key =>
              key.drop(1).dropRight(1) // Remove the encasing '{' and '}' characters.
            }.toSeq
            MatchSegment(exprSegment, Some(withCaptures), keyNames)
          case _ => MatchSegment(exprSegment, None, Seq.empty)
        }
      }
    }

    @tailrec
    private[this] def _extract(
      pathSegments: Seq[String],
      matchSegments: Seq[MatchSegment],
      vars: Map[String, String]
    ): Option[Map[String, String]] =
      (pathSegments.headOption, matchSegments.headOption) match {
        case (_, Some(MatchSegment("*", _, _))) =>
          _extract(pathSegments.tail, matchSegments.tail, vars)
        case (Some(path), Some(MatchSegment(expr, None, _))) if (expr == path) =>
          _extract(pathSegments.tail, matchSegments.tail, vars)
        case (Some(pathSegment), Some(MatchSegment(expr, Some(regex), Seq(segments@_*)))) =>
          regex.findAllIn(pathSegment) match {
            case v if !v.isEmpty =>
              _extract(
                pathSegments.tail,
                matchSegments.tail,
                vars ++ segments.zipWithIndex.map { case (s, i) => s -> v.group(i + 1) }
              )
            case _ => None
          }
        case (_, None) => Some(vars)
        case (_, _) => None
      }

    override def toString: String = expr
  }
}
