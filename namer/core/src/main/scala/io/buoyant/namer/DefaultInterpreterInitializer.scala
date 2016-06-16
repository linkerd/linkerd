package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.{NonFatal, Activity, Var}
import io.buoyant.namer.DelegateTree._
import scala.util.control.NoStackTrace
import scala.{Exception => ScalaException}

object DefaultInterpreterConfig {
  val kind = "default"
}

class DefaultInterpreterConfig extends InterpreterConfig {
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredNamersInterpreter(namers)
  }
}

class DefaultInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[DefaultInterpreterConfig]
  override val configId = DefaultInterpreterConfig.kind
}

object DefaultInterpreterInitializer extends DefaultInterpreterInitializer

case class UnknownNamer[Req](path: Path, cause: Throwable)
  extends ScalaException(s"Unknown namer: ${cause.getMessage} for path: ${path.show}", cause)
  with NoStackTrace

/**
 * Namers are provided in preference-order so that first-match wins.
 */
case class ConfiguredNamersInterpreter(namers: Seq[(Path, Namer)])
  extends NameInterpreter with Delegator {

  override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] =
    Namer.bind(lookup(dtab), NameTree.Leaf(path))

  def lookup(path: Path): Activity[NameTree[Name]] =
    lookup(namers, path)

  /** Try to refine the name through the dtab, or bind it through a configured namer. */
  private[this] def lookup(dtab: Dtab)(path: Path): Activity[NameTree[Name]] =
    dtab.lookup(path) match {
      case NameTree.Neg => lookup(namers, path)
      case t => Activity.value(t)
    }

  private[this] def lookup(remaining: Seq[(Path, Namer)], path: Path): Activity[NameTree[Name]] =
    remaining match {
      // For now, punt to the default interpreter. Later, we
      // should consider removing the finagle Namer.global, which
      // provides /$/ names.
      case Nil =>
        Namer.global.lookup(path).transform {
          case Activity.Failed(e: ClassNotFoundException) =>
            Activity.exception(UnknownNamer(path, e))
          case state => Activity(Var.value(state))
        }

      // try to lookup the path with the matching namer, or
      // fallback to the rest of the namers.
      case Seq((prefix, namer), remaining@_*) if path.startsWith(prefix) =>
        namer.lookup(path.drop(prefix.size)).flatMap {
          case NameTree.Neg => lookup(remaining, path)
          case t => Activity.value(t)
        }

      // Not a match, keep looking through namers.
      case Seq(_, remaining@_*) => lookup(remaining, path)
    }

  /* delegation */

  override def delegate(
    dtab: Dtab,
    tree: DelegateTree[Name.Path]
  ): Activity[DelegateTree[Bound]] = delegateBind(dtab, 0, tree).map(_.simplified)

  val MaxDepth = 100

  private[this] def delegateLookup(
    dtab: Dtab,
    dentry: Dentry,
    path: Path
  ): Activity[DelegateTree[Name]] = {
    val matches: Seq[DelegateTree[Name.Path]] = dtab.reverse.collect {
      case d@Dentry(prefix, dst) if prefix.matches(path) =>
        val suff = path.drop(prefix.size)
        fromNameTree(path, d, dst.map { pfx => Name.Path(pfx ++ suff) })
    }

    val result: DelegateTree[Name.Path] = matches match {
      case Nil => DelegateTree.Neg(path, dentry)
      case Seq(tree) => tree
      case trees => DelegateTree.Alt(path, dentry, trees: _*)
    }

    val lookup: Activity[DelegateTree[Name]] = result match {
      case DelegateTree.Neg(path, d) =>
        this.lookup(path).map(fromNameTree(path, d, _))
      case tree => Activity.value(tree)
    }

    lookup.handle { case NonFatal(e) => DelegateTree.Exception(path, dentry, e) }
  }

  private[this] def delegateBind(
    dtab: Dtab,
    depth: Int,
    tree: DelegateTree[Name]
  ): Activity[DelegateTree[Name.Bound]] =
    if (depth > MaxDepth)
      Activity.exception(new IllegalArgumentException("Max recursion level reached."))
    else tree match {
      case tree@Exception(_, _, _) => Activity.value(tree)
      case tree@Empty(_, _) => Activity.value(tree)
      case tree@Fail(_, _) => Activity.value(tree)
      case tree@Neg(_, _) => Activity.value(tree)

      case Leaf(path, dentry, bound@Name.Bound(_)) =>
        Activity.value(Leaf(path, dentry, bound))

      case Leaf(_, dentry, Name.Path(path)) =>
        // Resolve this leaf path through the dtab and bind the resulting tree.
        delegateLookup(dtab, dentry, path).flatMap { delegateTree =>
          delegateBind(dtab, depth + 1, delegateTree)
        }.map(Delegate(path, dentry, _))

      case Delegate(path, dentry, tree) =>
        delegateBind(dtab, depth, tree).map(Delegate(path, dentry, _))

      case Alt(path, dentry) => Activity.value(Neg(path, dentry))
      case Alt(path, dentry, tree) => delegateBind(dtab, depth, tree).map(Delegate(path, dentry, _))
      case Alt(path, dentry, trees@_*) =>
        // Unlike Namer.bind, we bind *all* alternate trees.
        val vars = trees.map(delegateBind(dtab, depth, _).run)
        val stateVar = Var.collect(vars).map { states =>
          val oks = states.collect { case Activity.Ok(t) => t }
          if (oks.nonEmpty) Activity.Ok(Alt(path, dentry, oks: _*))
          else states.collectFirst { case f@Activity.Failed(_) => f }.getOrElse(Activity.Pending)
        }
        Activity(stateVar)

      case Union(path, dentry) => Activity.value(Neg(path, dentry))
      case Union(path, dentry, Weighted(_, tree)) =>
        delegateBind(dtab, depth, tree).map(Delegate(path, dentry, _))
      case Union(path, dentry, trees@_*) =>
        val vars = trees.map {
          case Weighted(w, t) =>
            delegateBind(dtab, depth, t).map(Weighted(w, _)).run
        }
        val stateVar = Var.collect(vars).map { states =>
          val oks = states.collect { case Activity.Ok(t) => t }
          if (oks.nonEmpty) Activity.Ok(Union(path, dentry, oks: _*))
          else states.collectFirst { case f@Activity.Failed(_) => f }.getOrElse(Activity.Pending)
        }
        Activity(stateVar)
    }

  override def dtab: Activity[Dtab] = Activity.value(Dtab.empty)
}
