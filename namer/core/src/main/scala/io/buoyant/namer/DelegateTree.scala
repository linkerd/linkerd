package io.buoyant.namer

import com.twitter.finagle.{Dentry, NameTree, Path}
import com.twitter.util.Try

sealed trait DelegateTree[+T] {
  def path: Path
  def dentry: Dentry
  def map[U](f: T => U): DelegateTree[U] = DelegateTree.map(this, f)
  def simplified: DelegateTree[T] = DelegateTree.simplify(this)
  def toNameTree: NameTree[T] = DelegateTree.toNameTree(this)
  def withDentry(dentry: Dentry) = DelegateTree.withDentry(this, dentry)
}

object DelegateTree {

  case class Exception(path: Path, dentry: Dentry, thrown: Throwable) extends DelegateTree[Nothing]
  case class Empty(path: Path, dentry: Dentry) extends DelegateTree[Nothing]
  case class Fail(path: Path, dentry: Dentry) extends DelegateTree[Nothing]
  case class Neg(path: Path, dentry: Dentry) extends DelegateTree[Nothing]
  case class Delegate[+T](path: Path, dentry: Dentry, tree: DelegateTree[T]) extends DelegateTree[T]
  case class Leaf[+T](path: Path, dentry: Dentry, value: T) extends DelegateTree[T]
  case class Alt[+T](path: Path, dentry: Dentry, trees: DelegateTree[T]*) extends DelegateTree[T]
  case class Union[+T](path: Path, dentry: Dentry, trees: Weighted[T]*) extends DelegateTree[T]
  case class Weighted[+T](weight: Double, tree: DelegateTree[T]) {
    def map[U](f: T => U): Weighted[U] = copy(tree = tree.map(f))
  }

  private def withDentry[T](orig: DelegateTree[T], dentry: Dentry): DelegateTree[T] = orig match {
    case tree: Exception => tree.copy(dentry = dentry)
    case tree: Empty => tree.copy(dentry = dentry)
    case tree: Fail => tree.copy(dentry = dentry)
    case tree: Neg => tree.copy(dentry = dentry)
    case Delegate(path, _, tree) => Delegate(path, dentry, tree)
    case Leaf(path, _, v) => Leaf(path, dentry, v)
    case Alt(path, _, trees@_*) => Alt(path, dentry, trees: _*)
    case Union(path, _, trees@_*) => Union(path, dentry, trees: _*)
  }

  private def map[T, U](orig: DelegateTree[T], f: T => U): DelegateTree[U] = orig match {
    case tree: Exception => tree
    case tree: Empty => tree
    case tree: Fail => tree
    case tree: Neg => tree
    case Delegate(path, dentry, tree) => Delegate(path, dentry, tree.map(f))
    case Leaf(path, dentry, v) => Leaf(path, dentry, f(v))
    case Alt(path, dentry, trees@_*) => Alt(path, dentry, trees.map(_.map(f)): _*)
    case Union(path, dentry, trees@_*) => Union(path, dentry, trees.map(_.map(f)): _*)
  }

  private def simplify[T](tree: DelegateTree[T]): DelegateTree[T] = tree match {
    case Delegate(path, dentry, tree) =>
      val simplified = simplify(tree)
      val collapse = simplified match {
        case _: DelegateTree.Neg | _: DelegateTree.Fail | _: DelegateTree.Empty =>
          false
        case _ => simplified.path == path
      }
      if (collapse) simplified.withDentry(dentry)
      else Delegate(path, dentry, simplified)

    case Alt(path, dentry) => Neg(path, dentry)
    case Alt(path, dentry, tree) => simplify(Delegate(path, dentry, tree))
    case Alt(path, dentry, trees@_*) =>
      val simplified = trees.foldLeft(Seq.empty[DelegateTree[T]]) {
        case (trees, tree) => simplify(tree) match {
          case Alt(p, pf, ts@_*) if p == path =>
            trees ++ ts
          case tree =>
            trees :+ tree
        }
      }
      Alt(path, dentry, simplified: _*)

    case Union(path, dentry) => Neg(path, dentry)
    case Union(path, dentry, Weighted(_, tree)) => simplify(Delegate(path, dentry, tree))
    case Union(path, dentry, weights@_*) =>
      val simplified = weights.map {
        case Weighted(w, tree) => Weighted(w, simplify(tree))
      }
      Union(path, dentry, simplified: _*)

    case tree => tree
  }

  private def toNameTree[T](delegates: DelegateTree[T]): NameTree[T] = delegates match {
    case Exception(_, _, e) => throw e
    case Empty(_, _) => NameTree.Empty
    case Fail(_, _) => NameTree.Fail
    case Neg(_, _) => NameTree.Neg
    case Delegate(_, _, tree) => toNameTree(tree)
    case Leaf(_, _, v) => NameTree.Leaf(v)
    case Alt(_, _, delegates@_*) => NameTree.Alt(delegates.map(toNameTree): _*)
    case Union(_, _, delegates@_*) => NameTree.Union(delegates.map(toNameTreeWeighted): _*)
  }

  private def toNameTreeWeighted[T](delegate: DelegateTree.Weighted[T]): NameTree.Weighted[T] =
    NameTree.Weighted(delegate.weight, toNameTree(delegate.tree))

  def fromNameTree[T](path: Path, dentry: Dentry, names: NameTree[T]): DelegateTree[T] =
    names match {
      case NameTree.Empty => DelegateTree.Empty(path, dentry)
      case NameTree.Fail => DelegateTree.Fail(path, dentry)
      case NameTree.Neg => DelegateTree.Neg(Path.empty, dentry)
      case NameTree.Leaf(v) => DelegateTree.Leaf(path, dentry, v)
      case NameTree.Alt(names@_*) =>
        val delegates = names.map(fromNameTree[T](path, dentry, _))
        DelegateTree.Alt(path, dentry, delegates: _*)
      case NameTree.Union(names@_*) =>
        val delegates = names.map {
          case NameTree.Weighted(w, tree) =>
            DelegateTree.Weighted(w, fromNameTree(path, dentry, tree))
        }
        DelegateTree.Union(path, dentry, delegates: _*)
    }

  def fromNameTree[T](names: NameTree[T]): DelegateTree[T] =
    names match {
      case NameTree.Empty => DelegateTree.Empty(null, Dentry.nop)
      case NameTree.Fail => DelegateTree.Fail(null, Dentry.nop)
      case NameTree.Neg => DelegateTree.Neg(null, Dentry.nop)
      case NameTree.Leaf(v) => DelegateTree.Leaf(null, Dentry.nop, v)
      case NameTree.Alt(names@_*) =>
        val delegates = names.map(fromNameTree[T](null, Dentry.nop, _))
        DelegateTree.Alt(null, Dentry.nop, delegates: _*)
      case NameTree.Union(names@_*) =>
        val delegates = names.map {
          case NameTree.Weighted(w, tree) =>
            DelegateTree.Weighted(w, fromNameTree(null, Dentry.nop, tree))
        }
        DelegateTree.Union(null, Dentry.nop, delegates: _*)
    }
}
