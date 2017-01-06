package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Dtab, Name, NameTree, Path}
import com.twitter.util.Activity

/** Bind the given path and replace all bound names in the tree with it. */
class ReplaceTransformer(prefix: Path, path: Path) extends NameTreeTransformer {

  private[this] lazy val bound = NameInterpreter.global.bind(Dtab.empty, path).map { tree =>
    tree.map { b =>
      Name.Bound(b.addr, prefix ++ path, b.path)
    }
  }

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] = {
    bound.map { replacement =>
      replace(tree, replacement)
    }
  }

  /** Replace all NameTree.Leafs in tree with replacement */
  private[this] def replace[T](tree: NameTree[T], replacement: NameTree[T]): NameTree[T] = tree match {
    case NameTree.Neg | NameTree.Empty | NameTree.Fail => tree
    case NameTree.Alt(trees@_*) =>
      NameTree.Alt(trees.map(replace(_, replacement)): _*)
    case NameTree.Union(weighted@_*) =>
      NameTree.Union(weighted.map {
        case NameTree.Weighted(w, t) => NameTree.Weighted(w, replace(t, replacement))
      }: _*)
    case NameTree.Leaf(_) => replacement
  }
}
