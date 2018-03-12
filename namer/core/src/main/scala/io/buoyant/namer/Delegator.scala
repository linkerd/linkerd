package io.buoyant.namer

import com.twitter.finagle.{Dentry, Dtab, Name, NameTree, Path}
import com.twitter.util.{Activity, Future}

trait Delegator {

  def delegate(
    dtab: Dtab,
    tree: NameTree[Name.Path]
  ): Future[DelegateTree[Name.Bound]]

  final def delegate(
    dtab: Dtab,
    path: Path
  ): Future[DelegateTree[Name.Bound]] =
    delegate(dtab, NameTree.Leaf(Name.Path(path)))

  def dtab: Activity[Dtab]
}
