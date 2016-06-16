package io.buoyant.namerd

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.Activity
import io.buoyant.namer.{ConfiguredNamersInterpreter, DelegateTree, Delegator}

case class ConfiguredDtabNamer(
  configuredDtab: Activity[Dtab],
  namers: Seq[(Path, Namer)] = Nil
) extends NameInterpreter with Delegator {

  def bind(localDtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] =
    configuredDtab.flatMap { configuredDtab =>
      Namer.bind(lookup(configuredDtab ++ localDtab), NameTree.Leaf(path))
    }

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
      case Nil => Namer.global.lookup(path)

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

  private[this] def namersInterpreter = ConfiguredNamersInterpreter(namers)

  override def delegate(
    dtab: Dtab,
    tree: DelegateTree[Name.Path]
  ): Activity[DelegateTree[Bound]] =
    configuredDtab.flatMap { confDtab =>
      namersInterpreter.delegate(confDtab ++ dtab, tree)
    }

  override def dtab: Activity[Dtab] = configuredDtab
}
