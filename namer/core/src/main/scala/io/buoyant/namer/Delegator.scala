package io.buoyant.namer

import com.twitter.finagle._
import com.twitter.util.Activity

trait Delegator {

  def delegate(
    dtab: Dtab,
    tree: DelegateTree[Name.Path]
  ): Activity[DelegateTree[Name.Bound]]

  final def delegate(
    dtab: Dtab,
    path: Path
  ): Activity[DelegateTree[Name.Bound]] =
    delegate(dtab, DelegateTree.Leaf(path, Dentry.nop, Name.Path(path)))

  def dtab: Activity[Dtab]
}
