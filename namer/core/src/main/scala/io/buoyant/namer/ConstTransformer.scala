package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Dtab, NameTree, Path}
import com.twitter.util.Activity

/** Bind the given path and use that instead of the tree. */
class ConstTransformer(path: Path) extends NameTreeTransformer {

  private[this] lazy val bound = NameInterpreter.global.bind(Dtab.empty, path)

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] =
    bound
}
