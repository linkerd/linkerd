package io.buoyant

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.util.{Activity}

/**
 * A helper
 */
private[buoyant] trait RewritingNamer extends Namer {
  protected[this] def rewrite(orig: Path): Option[Path]

  def lookup(path: Path): Activity[NameTree[Name]] =
    Activity.value(path match {
      case path if path.size > 0 =>
        rewrite(path) match {
          case Some(path) => NameTree.Leaf(Name.Path(path))
          case None => NameTree.Neg
        }
      case _ => NameTree.Neg
    })
}
