package com.twitter.finagle.buoyant

import com.twitter.finagle.{Path => FPath, _}
import com.twitter.finagle.naming.{DefaultInterpreter, NameInterpreter}
import com.twitter.finagle.util.Showable
import com.twitter.util.Var

sealed trait Dst {
  def path: FPath
}

object Dst {

  case class Path(path: FPath, baseDtab: Dtab = Dtab.empty, localDtab: Dtab = Dtab.empty) extends Dst {
    def dtab: Dtab = baseDtab ++ localDtab

    def bind(namer: NameInterpreter = DefaultInterpreter) =
      namer.bind(baseDtab ++ localDtab, path).map(BoundTree(_, path))

    def mk(): (Path, Stack.Param[Path]) = (this, Path)

    /*
     * XXX the baseDtab should not be considered for equality/hashing, since it's
     * potentially very expensive.
     */
    override def hashCode() = (path, localDtab).hashCode()
    override def equals(other: Any) = other match {
      case Path(p, _, d) => path == p && localDtab == d
      case _ => false
    }
  }

  implicit object Path extends Stack.Param[Path] {
    val empty = Path(FPath.empty, Dtab.empty, Dtab.empty)
    val default = Path(FPath.read("/$/fail"), Dtab.base, Dtab.local)
  }

  /**
   * Wraps a Name.Bound but takes the residual path into account when
   * computing equality.
   */
  class Bound private (val name: Name.Bound) extends Dst with Proxy {
    val self = (name.id, name.path)

    def addr = name.addr
    def id = name.id
    def idStr = name.idStr
    def path = name.path

    def mk(): (Bound, Stack.Param[Bound]) = (this, Bound)
  }

  implicit object Bound extends Stack.Param[Bound] {
    def apply(name: Name.Bound): Bound =
      new Bound(name)

    def apply(addr: Var[Addr], id: FPath, path: FPath = FPath.empty): Bound =
      new Bound(Name.Bound(addr, id, path))

    def unapply(bound: Name.Bound): Option[(Var[Addr], String, FPath)] =
      Some((bound.addr, bound.idStr, bound.path))

    // So that NameTree[Bound] may be shown.
    implicit val showable: Showable[Bound] = new Showable[Bound] {
      def show(bound: Bound) = Showable.show(bound.name)
    }

    val default = Dst.Bound(Var.value(Addr.Neg), FPath.read("/$/neg"))
  }

  /**
   * Wraps NameTree[Bound] with the original name that was resolved
   * (i.e. for debugging).
   */
  class BoundTree(
    val nameTree: NameTree[Bound],
    val path: FPath
  ) extends Proxy {
    def self = nameTree
    def show = nameTree.show
  }

  object BoundTree {
    def apply(tree: NameTree[Name.Bound], path: FPath): BoundTree =
      new BoundTree(tree.map(Bound(_)), path)

    def unapply(t: BoundTree): Option[(NameTree[Bound], FPath)] =
      Some((t.nameTree, t.path))
  }
}
