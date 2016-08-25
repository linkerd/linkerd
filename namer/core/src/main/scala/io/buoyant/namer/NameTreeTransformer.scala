package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle._
import com.twitter.util.Activity

trait NameTreeTransformer {

  protected def transform(tree: NameTree[Name.Bound]): Activity[NameTree[Name.Bound]]

  def wrap(underlying: NameInterpreter): NameInterpreter = new NameInterpreter {
    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] =
      underlying.bind(dtab, path).flatMap(transform)
  }
}

trait DelegatingNameTreeTransformer extends NameTreeTransformer {

  protected def transformDelegate(tree: DelegateTree[Name.Bound]): DelegateTree[Name.Bound]

  def delegatingWrap(underlying: NameInterpreter with Delegator): NameInterpreter with Delegator = new NameInterpreter with Delegator {
    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] =
      underlying.bind(dtab, path).flatMap(transform)

    override def delegate(
      dtab: Dtab,
      tree: DelegateTree[Name.Path]
    ): Activity[DelegateTree[Bound]] = underlying.delegate(dtab, tree).map(transformDelegate)

    override def dtab: Activity[Dtab] = underlying.dtab
  }
}
