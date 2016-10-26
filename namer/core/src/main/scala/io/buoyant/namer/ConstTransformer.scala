package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.{NameTree, Namer, Path}
import com.twitter.util.Activity

/** Bind the given path and use that instead of the tree. */
class ConstTransformer(path: Path) extends NameTreeTransformer {

  private[this] val bound = Namer.global.bind(NameTree.Leaf(path))

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] =
    bound
}
