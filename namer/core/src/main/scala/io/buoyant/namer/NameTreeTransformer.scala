package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle._
import com.twitter.util.{Activity, Future}
import io.buoyant.admin.Admin
import io.buoyant.admin.Admin.Handler

/**
 * A NameTreeTransformer performs some kind of transformation on bound
 * NameTrees.  These transformers are generally applied to the output of a
 * NameInterpreter to do post-processing.
 */

trait WithNameTreeTransformer {
  def transformers: Seq[NameTreeTransformer]
}

trait NameTreeTransformer {

  protected def transform(tree: NameTree[Name.Bound]): Activity[NameTree[Name.Bound]]

  /**
   * Create a new NameInterpreter by applying this transformer to the output of
   * an existing one.
   */
  def wrap(underlying: NameInterpreter): NameInterpreter = new NameInterpreter with Admin.WithHandlers with WithNameTreeTransformer {
    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] =
      underlying.bind(dtab, path).flatMap(transform)

    override def adminHandlers: Seq[Admin.Handler] = underlying match {
      case withHandlers: Admin.WithHandlers => withHandlers.adminHandlers
      case _ => Nil
    }

    override def transformers: Seq[NameTreeTransformer] = underlying match {
      case withNameTreeTransformer: WithNameTreeTransformer => withNameTreeTransformer.transformers ++ Seq(getSelf())
      case _ => Seq(getSelf())
    }
  }

  def getSelf(): NameTreeTransformer = this

  def wrap(underlying: Namer): Namer = new Namer with Admin.WithHandlers with WithNameTreeTransformer {
    private[this] def isBound(tree: NameTree[Name]): Boolean = {
      tree match {
        case NameTree.Neg | NameTree.Empty | NameTree.Fail => true
        case NameTree.Alt(trees@_*) => trees.forall(isBound)
        case NameTree.Union(trees@_*) => trees.map(_.tree).forall(isBound)
        case NameTree.Leaf(_: Name.Bound) => true
        case NameTree.Leaf(_) => false
      }
    }

    override def lookup(path: Path): Activity[NameTree[Name]] = {
      underlying.lookup(path).flatMap { tree =>
        if (isBound(tree))
          transform(tree.asInstanceOf[NameTree[Name.Bound]])
        else
          Activity.value(tree)
      }
    }

    override def transformers: Seq[NameTreeTransformer] = underlying match {
      case withNameTreeTransformer: WithNameTreeTransformer => withNameTreeTransformer.transformers ++ Seq(getSelf())
      case _ => Seq(getSelf())
    }

    override def adminHandlers: Seq[Admin.Handler] = underlying match {
      case withHandlers: Admin.WithHandlers => withHandlers.adminHandlers
      case _ => Nil
    }
  }
}

/**
 * A DelegatingNameTreeTransformer is a NameTreeTransformer that can transform
 * DelegateTrees as well as NameTrees.  This allows a NameInterpreter to
 * preserve the ability to delegate when wrapped by this transformer.
 */
trait DelegatingNameTreeTransformer extends NameTreeTransformer {

  protected def transformDelegate(tree: DelegateTree[Name.Bound]): Future[DelegateTree[Name.Bound]]

  /** Like wrap, but preserving the ability of the NameInterpreter to delegate */
  def delegatingWrap(underlying: NameInterpreter with Delegator): NameInterpreter with Delegator = new NameInterpreter with Delegator with Admin.WithHandlers with WithNameTreeTransformer {
    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] =
      underlying.bind(dtab, path).flatMap(transform)

    override def delegate(
      dtab: Dtab,
      tree: NameTree[Name.Path]
    ): Future[DelegateTree[Bound]] =
      underlying.delegate(dtab, tree).flatMap(transformDelegate)

    override def dtab: Activity[Dtab] = underlying.dtab

    override def adminHandlers: Seq[Admin.Handler] = underlying match {
      case withHandlers: Admin.WithHandlers => withHandlers.adminHandlers
      case _ => Nil
    }

    override def transformers: Seq[NameTreeTransformer] = underlying match {
      case withNameTreeTransformer: WithNameTreeTransformer => withNameTreeTransformer.transformers ++ Seq(getSelf())
      case _ => Seq(getSelf())
    }
  }
}

object DelegatingNameTreeTransformer {

  /**
   * Expand a delegate tree to show the effect of a transformation that maps one Name.Bound to
   * another.
   */
  def transformDelegate(tree: DelegateTree[Name.Bound], mapBound: Name.Bound => Name.Bound): DelegateTree[Name.Bound] =
    tree.flatMap { orig =>
      val transformed = mapBound(orig.value)
      val transformedPath = transformed.id match {
        case id: Path => id
        case _ => orig.path
      }
      DelegateTree.Transformation(
        orig.path,
        getClass.getSimpleName,
        orig.value, // pre-transformation
        orig.copy(value = transformed, path = transformedPath) // post-transformation
      )
    }
}

trait FilteringNameTreeTransformer extends DelegatingNameTreeTransformer {

  def prefix: Path

  /** Determine whether an address may be used. */
  protected def predicate: Address => Boolean

  private[this] val mapBound: Name.Bound => Name.Bound = { bound =>
    val vaddr = bound.addr.map {
      case Addr.Bound(addrs, meta) =>
        addrs.filter(predicate) match {
          case filtered if filtered.isEmpty => Addr.Neg
          case filtered => Addr.Bound(filtered, meta)
        }
      case addr => addr
    }
    bound.id match {
      case id: Path => Name.Bound(vaddr, prefix ++ id, bound.path)
      case _ => Name.Bound(vaddr, bound.id, bound.path)
    }

  }

  override protected def transformDelegate(tree: DelegateTree[Name.Bound]): Future[DelegateTree[Name.Bound]] =
    Future.value(tree.flatMap { leaf =>
      val bound = mapBound(leaf.value)
      val path = bound.id match {
        case id: Path => id
        case _ => leaf.path
      }
      DelegateTree.Transformation(
        leaf.path,
        getClass.getSimpleName,
        leaf.value,
        leaf.copy(value = bound, path = path)
      )
    })

  override protected def transform(tree: NameTree[Name.Bound]): Activity[NameTree[Name.Bound]] =
    Activity.value(tree.map(mapBound))
}

class MetadataFiltertingNameTreeTransformer(
  val prefix: Path,
  metadataKey: String,
  metadataValue: Any
) extends FilteringNameTreeTransformer {
  protected val predicate: Address => Boolean = {
    case a@Address.Inet(_, meta) =>
      meta.get(metadataKey).contains(metadataValue)
    case _ => true
  }
}
