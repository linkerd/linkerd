package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle._
import com.twitter.util.Activity

/**
 * A NameTreeTransformer performs some kind of transformation on bound
 * NameTrees.  These transformers are generally applied to the output of a
 * NameInterpreter to do post-processing.
 */
trait NameTreeTransformer {

  protected def transform(tree: NameTree[Name.Bound]): Activity[NameTree[Name.Bound]]

  /**
   * Create a new NameInterpreter by applying this transformer to the output of
   * an existing one.
   */
  def wrap(underlying: NameInterpreter): NameInterpreter = new NameInterpreter {
    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] =
      underlying.bind(dtab, path).flatMap(transform)
  }
}

/**
 * A DelegatingNameTreeTransformer is a NameTreeTransformer that can transform
 * DelegateTrees as well as NameTrees.  This allows a NameInterpreter to
 * preserve the ability to delegate when wrapped by this transformer.
 */
trait DelegatingNameTreeTransformer extends NameTreeTransformer {

  protected def transformDelegate(tree: DelegateTree[Name.Bound]): Activity[DelegateTree[Name.Bound]]

  /** Like wrap, but preserving the ability of the NameInterpreter to delegate */
  def delegatingWrap(underlying: NameInterpreter with Delegator): NameInterpreter with Delegator = new NameInterpreter with Delegator {
    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] =
      underlying.bind(dtab, path).flatMap(transform)

    override def delegate(
      dtab: Dtab,
      tree: DelegateTree[Name.Path]
    ): Activity[DelegateTree[Bound]] = underlying.delegate(dtab, tree).flatMap(transformDelegate)

    override def dtab: Activity[Dtab] = underlying.dtab
  }
}
