package io.buoyant.namer

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.util.Activity
import io.buoyant.namer.util.PathMatcher

class RewritingNamer(matcher: PathMatcher, pattern: String) extends Namer {
  override def lookup(path: Path): Activity[NameTree[Name]] = matcher.substitutePath(path, pattern) match {
    case Some(result) => Activity.value(NameTree.Leaf(Name(result)))
    case None => Activity.value(NameTree.Neg)
  }
}
