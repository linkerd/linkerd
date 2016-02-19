package io.buoyant.linkerd

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Dtab, Name, NameTree, Namer, Path}
import com.twitter.util.Activity

case class Interpreter(namers: Seq[(Path, Namer)] = Seq.empty)
  extends NameInterpreter {

  private[this] lazy val processOrderNamers = namers.reverse

  override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] =
    Namer.bind(lookup(dtab), NameTree.Leaf(path))

  def lookup(path: Path): Activity[NameTree[Name]] =
    lookup(processOrderNamers, path)

  /** Try to refine the name through the dtab, or bind it through a configured namer. */
  private[this] def lookup(dtab: Dtab)(path: Path): Activity[NameTree[Name]] =
    dtab.lookup(path) match {
      case NameTree.Neg => lookup(processOrderNamers, path)
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
}

