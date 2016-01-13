package io.buoyant.linkerd

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.twitter.finagle.{Dtab, Name, NameTree, Namer, Path}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.util.LoadService
import com.twitter.util.Activity

/**
 * Holds a suite of namer initializers, which may be used to process a
 * configuration block into a `NameInterpreter`.
 */
trait NamerInitializers {
  def kinds: Set[String]
  def get(k: String): Option[NamerInitializer]

  /**
   * Read a list of [[NamerInitializer]] configurations into a
   * `com.twitter.finagle.naming.NameInterpreter` that may be used by
   * `io.buoyant.router.Router`s.
   *
   * The order of namers matters, as namers *may* have overlapping
   * prefixes and earlier namers are preferred over latter namers.  If
   * an earlier fails to resolve a name (i.e. by yielding a
   * NameTree.Neg), then latter namers will be tried.
   */
  def read(p: JsonParser): NameInterpreter
}

object NamerInitializers {

  def apply(n: NamerInitializer, ns: NamerInitializer*): NamerInitializers =
    apply(n +: ns)

  def apply(ns: Seq[NamerInitializer]): NamerInitializers = {
    val byKind = ns.foldLeft(Map.empty[String, NamerInitializer]) {
      case (accum, n) =>
        val kind = n.getClass.getName
        if (accum contains kind) {
          throw new IllegalArgumentException(s"NamerInitializer kind conflict: '$kind'")
        } else {
          accum + (kind -> n)
        }
    }
    _NamerInitializers(byKind)
  }

  /**
   * Runtime-loaded namer initializers.
   *
   * Uses finagle's `LoadService` facility to discover protocol
   * support at runtime by searching the class path for
   * NamerInitializer subclasses.
   */
  def load(): NamerInitializers =
    apply(LoadService[NamerInitializer]())

  private case class _NamerInitializers(initializers: Map[String, NamerInitializer])
    extends NamerInitializers {

    def kinds = initializers.keySet
    def get(k: String) = initializers.get(k)

    def read(p: JsonParser): NameInterpreter =
      Parsing.foldArray(p, Interpreter()) {
        case (interpreter, p) =>
          Parsing.ensureTok(p, JsonToken.START_OBJECT) { p =>
            val ni = NamerInitializer.read(this.get, p)
            ni.prefix match {
              case Path.empty => throw Parsing.error("namer prefix required", p)
              case prefix => interpreter.naming(prefix, ni.newNamer())
            }
          }
      }
  }

  val empty: NamerInitializers = _NamerInitializers(Map.empty)

  private case class Interpreter(namers: Seq[(Path, Namer)] = Seq.empty)
    extends NameInterpreter {
    private[NamerInitializers] def naming(p: Path, n: Namer) =
      copy(namers = namers :+ (p -> n))

    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] =
      Namer.bind(lookup(dtab), NameTree.Leaf(path))

    /** Try to refine the name through the dtab, or bind it through a configured namer. */
    private[this] def lookup(dtab: Dtab)(path: Path): Activity[NameTree[Name]] =
      dtab.lookup(path) match {
        case NameTree.Neg => lookup(namers, path)
        case t => Activity.value(t)
      }

    private[this] def lookup(namers: Seq[(Path, Namer)], path: Path): Activity[NameTree[Name]] =
      namers match {
        // For now, punt to the default interpreter. Later, we
        // should consider removing the finagle Namer.global, which
        // provides /$/ names.
        case Nil => Namer.global.lookup(path)

        // try to lookup the path with the matching namer, or
        // fallback to the rest of the namers.
        case Seq((prefix, namer), namers@_*) if path.startsWith(prefix) =>
          namer.lookup(path.drop(prefix.size)).flatMap {
            case NameTree.Neg => lookup(namers, path)
            case t => Activity.value(t)
          }

        // Not a match, keep looking through namers.
        case Seq(_, namers@_*) => lookup(namers, path)
      }
  }
}
