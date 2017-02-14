package io.linkerd.mesh

import com.twitter.finagle
import io.buoyant.grpc.runtime.GrpcStatus
import io.linkerd.mesh

/**
 * Utilities for converting
 */
object Converters {

  val fromPath: mesh.Path => finagle.Path =
    ppath => finagle.Path(ppath.elems: _*)

  val toPath: finagle.Path => mesh.Path =
    path => mesh.Path(path.elems)

  val toDtab: finagle.Dtab => mesh.Dtab =
    dtab => mesh.Dtab(dtab.map(toDentry))

  val fromDtab: mesh.Dtab => finagle.Dtab =
    pdtab => finagle.Dtab(pdtab.dentries.toIndexedSeq.map(fromDentry))

  val toDentry: finagle.Dentry => mesh.Dtab.Dentry = { dentry =>
    val ppfx = toPrefix(dentry.prefix)
    val pdst = toPathNameTree(dentry.dst)
    mesh.Dtab.Dentry(Some(ppfx), Some(pdst))
  }

  val fromDentry: mesh.Dtab.Dentry => finagle.Dentry = {
    case mesh.Dtab.Dentry(Some(ppfx), Some(pdst)) =>
      val pfx = fromPrefix(ppfx)
      val dst = fromPathNameTree(pdst)
      finagle.Dentry(pfx, dst)
    case dentry =>
      throw new IllegalArgumentException(s"Illegal dentry: $dentry")
  }

  private[this] val WildcardElem =
    mesh.Dtab.Dentry.Prefix.Elem(Some(
      mesh.Dtab.Dentry.Prefix.Elem.OneofValue.Wildcard(
        mesh.Dtab.Dentry.Prefix.Elem.Wildcard()
      )
    ))

  val toPrefix: finagle.Dentry.Prefix => mesh.Dtab.Dentry.Prefix =
    pfx => mesh.Dtab.Dentry.Prefix(pfx.elems.map {
      case finagle.Dentry.Prefix.AnyElem => WildcardElem
      case finagle.Dentry.Prefix.Label(buf) =>
        mesh.Dtab.Dentry.Prefix.Elem(Some(mesh.Dtab.Dentry.Prefix.Elem.OneofValue.Label(buf)))
    })

  val fromPrefix: mesh.Dtab.Dentry.Prefix => finagle.Dentry.Prefix =
    ppfx => finagle.Dentry.Prefix(ppfx.elems.map(_fromPrefixElem): _*)

  private[this] val _fromPrefixElem: mesh.Dtab.Dentry.Prefix.Elem => finagle.Dentry.Prefix.Elem = {
    case WildcardElem => finagle.Dentry.Prefix.AnyElem
    case mesh.Dtab.Dentry.Prefix.Elem(Some(mesh.Dtab.Dentry.Prefix.Elem.OneofValue.Label(buf))) =>
      finagle.Dentry.Prefix.Label(buf)
    case elem =>
      throw new IllegalArgumentException(s"Illegal prefix element: $elem")
  }

  val toPathNameTree: finagle.NameTree[finagle.Path] => mesh.PathNameTree = {
    case finagle.NameTree.Neg =>
      mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Neg(mesh.PathNameTree.Neg())))

    case finagle.NameTree.Fail =>
      mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Fail(mesh.PathNameTree.Fail())))

    case finagle.NameTree.Empty =>
      mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Empty(mesh.PathNameTree.Empty())))

    case finagle.NameTree.Leaf(path: finagle.Path) =>
      val pp = toPath(path)
      val leaf = mesh.PathNameTree.OneofNode.Leaf(mesh.PathNameTree.Leaf(Some(pp)))
      mesh.PathNameTree(Some(leaf))

    case finagle.NameTree.Alt(trees@_*) =>
      val alt = mesh.PathNameTree.OneofNode.Alt(mesh.PathNameTree.Alt(trees.map(toPathNameTree)))
      mesh.PathNameTree(Some(alt))

    case finagle.NameTree.Union(trees@_*) =>
      val weighted = trees.map { wt =>
        mesh.PathNameTree.Union.Weighted(Some(wt.weight), Some(toPathNameTree(wt.tree)))
      }
      mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Union(mesh.PathNameTree.Union(weighted))))
  }

  val fromPathNameTree: mesh.PathNameTree => finagle.NameTree[finagle.Path] = {
    case mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Neg(_))) => finagle.NameTree.Neg
    case mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Fail(_))) => finagle.NameTree.Fail
    case mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Empty(_))) => finagle.NameTree.Empty

    case mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Leaf(mesh.PathNameTree.Leaf(Some(path))))) =>
      finagle.NameTree.Leaf(fromPath(path))

    case mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Alt(mesh.PathNameTree.Alt(ptrees)))) =>
      val trees = ptrees.map(fromPathNameTree)
      finagle.NameTree.Alt(trees: _*)

    case mesh.PathNameTree(Some(mesh.PathNameTree.OneofNode.Union(mesh.PathNameTree.Union(ptrees)))) =>
      val trees = ptrees.collect {
        case mesh.PathNameTree.Union.Weighted(Some(weight), Some(ptree)) =>
          finagle.NameTree.Weighted(weight, fromPathNameTree(ptree))
      }
      finagle.NameTree.Union(trees: _*)

    case tree =>
      throw new IllegalArgumentException(s"illegal name tree: $tree")
  }

}
