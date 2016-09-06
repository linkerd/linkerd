package io.buoyant.dtree

import com.twitter.finagle.{Dtab, Name, NameTree, Path}
import com.twitter.io.Buf

trait Dtree {
  def subtree(elem: Buf): Option[Dtree]
  def dtab: Dtab

  final def delegate(path: Path, local: Dtab): NameTree[Name.Path] =
    delegate(NameTree.Leaf(Name.Path(path)), local)

  final def delegate(tree: NameTree[Name.Path], local: Dtab): NameTree[Name.Path] =
    Dtree.delegate(this, tree, local)
}

object Dtree {

  def delegate(root: Dtree, path: Path, local: Dtab = Dtab.empty): NameTree[Name.Path] =
    delegate(root, NameTree.Leaf(Name.Path(path)), local)

  def delegate(root: Dtree, tree: NameTree[Name.Path], local: Dtab): NameTree[Name.Path] =
    refineTree(root, tree, local).simplified

  private[this] def refineTree(
    root: Dtree,
    tree0: NameTree[Name.Path],
    local: Dtab
  ): NameTree[Name.Path] = {
    def refine(tree: NameTree[Name.Path]): NameTree[Name.Path] = {
      val refined = tree match {
        case NameTree.Leaf(Name.Path(path)) =>
          refinePath(root, path, local) match {
            case t if t == tree => tree
            case t => refine(t)
          }
        case NameTree.Alt(trees@_*) =>
          val refined = trees.map(refine(_))
          NameTree.Alt(refined: _*)
        case NameTree.Union(trees@_*) =>
          val refined = trees.map {
            case NameTree.Weighted(w, t) => NameTree.Weighted(w, refine(t))
          }
          NameTree.Union(refined: _*)
        case tree => tree
      }
      refined
    }

    refine(tree0)
  }

  private[this] def refinePath(
    node0: Dtree,
    path0: Path,
    local: Dtab
  ): NameTree[Name.Path] = {
    def refine(node: Dtree, path: Path): NameTree[Name.Path] = {
      val tree = path match {
        case Path.empty => NameTree.Leaf(Name.Path(path0))
        case Path(elem, _*) =>
          node.subtree(elem) match {
            case None => NameTree.Leaf(Name.Path(path0))
            case Some(t) => refine(t, path.drop(1))
          }
      }
      lookup(tree, node.dtab ++ local)
    }

    refine(node0, path0)
  }

  /** Apply the given Dtab to a NameTree. */
  private[this] def lookup(tree: NameTree[Name.Path], dtab: Dtab): NameTree[Name.Path] = {
    tree match {
      case leaf@NameTree.Leaf(Name.Path(path)) =>
        dtab.lookup(path) match {
          case NameTree.Neg => leaf
          case t@NameTree.Leaf(Name.Path(p)) if p == path => t
          case t => lookup(t, dtab)
        }
      case NameTree.Alt(trees@_*) =>
        val delegated = trees.map(lookup(_, dtab))
        NameTree.Alt(delegated: _*)
      case NameTree.Union(trees@_*) =>
        val delegated = trees.map {
          case NameTree.Weighted(w, t) => NameTree.Weighted(w, lookup(t, dtab))
        }
        NameTree.Union(delegated: _*)
      case tree => tree
    }
  }

}

trait MapDtree extends Dtree {
  def tree: Map[String, Dtree]
  def subtree(elem: Buf) = {
    val Buf.Utf8(k) = elem
    tree.get(k)
  }
}
