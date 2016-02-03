package io.buoyant.linkerd.util

import com.twitter.finagle.Path
import scala.util.parsing.combinator.RegexParsers

/**
 * A utility for rewriting paths.
 *
 * A PathMatcher accept a rewrite expression supporting numerical
 * positional arguments (much like awk), describing path positions.
 *
 * For example, given a rewrite expression like `$2.$1` and a path,
 * `/io/buoyant`, `buoyant.io` is returned.
 */
trait PathMatcher extends (Path => Option[String]) {
  def expression: String

  def apply(path: Path): Option[String]

  def mkPath(path: Path): Option[Path] = apply(path) match {
    case None => None
    case Some(p) =>
      try Some(Path.read(p))
      catch { case e: IllegalArgumentException => None }
  }
}

object PathMatcher {

  /**
   * Build a PathMatcher from an expression.
   *
   * Expressions contain positional parameters describing the segment
   * of a path.  A positional parameter may be in the forms:
   * - $1
   * - ${1}
   * - $01
   * - etc, no math though
   *
   * For example, any of these would refer to "foo" in the path
   * "/foo/bar".  When used in an expression like "$2.$1.com", this
   * would expand to "bar.foo.com".
   *
   * This function should only be called during configuration (and not
   * in the serving path).
   */
  def apply(expr: String): PathMatcher = new Matcher(expr)

  private class Matcher(val expression: String) extends PathMatcher {
    private[this] val exprs = Parser.read(expression)

    def apply(path: Path): Option[String] =
      exprs match {
        // special-case exprs that don't contain positional parameters
        case Seq(Parser.Text(text)) => Some(text)
        case exprs =>
          // Pardon our proceduralness.  We're trying to be conservative
          // about allocation in what might potentially be the serving
          // path.
          val parts = {
            val Path.Utf8(parts@_*) = path
            parts.toArray
          }
          val accum = new StringBuilder
          var i = 0
          while (i != exprs.length) {
            val part = exprs(i) match {
              case Parser.Text(part) => part
              case Parser.Position(pos) =>
                if (pos <= 0 || parts.length < pos) {
                  // short-circuit-fail if the position is out of range
                  return None
                }
                parts(pos - 1)
            }
            accum ++= part
            i += 1
          }
          Some(accum.result)
      }
  }

  /**
   * Part of a Name expression -- either a positional argument or text.
   */
  private object Parser extends RegexParsers {
    sealed trait Expr
    case class Text(text: String) extends Expr
    case class Position(value: Int) extends Expr {
      require(value > 0)
    }

    private[this] val number = """\d+""".r ^^ (_.toInt)

    // positional parameter: $N or ${N}
    private[this] val position = "$" ~> (number | ("{" ~> number <~ "}")) ^^ (Position(_))

    // text can't have $
    private[this] val text = """[^$]+""".r ^^ (Text(_))

    private[this] val expr: Parser[Seq[Expr]] = (position | text).*

    /** This should only be called during configuration (and not in the serving path). */
    def read(input: String): Seq[Expr] = parseAll(expr, input) match {
      case Success(exprs, _) => exprs
      case err: NoSuccess =>
        throw new IllegalArgumentException(s"invalid name expression: $input")
    }
  }

}

