// modified from com.twitter.serverset | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
package io.buoyant.namer.serversets

import com.twitter.finagle._
import com.twitter.util.{Activity, Var, Try, Return, Throw}
import com.twitter.finagle.serverset2.{BouyantZkResolver, BouyantZkResolverImpl}

/**
 * The serverset namer takes Paths of the form
 *
 * {{{
 * hosts/path...
 * }}}
 *
 * and returns a dynamic representing of the resolution of the path into a
 * tree of Names.
 *
 * The namer synthesizes nodes for each endpoint in the serverset.
 * Endpoint names are delimited by the ':' character. For example
 *
 * {{{
 * /#/io.l5d.serversets/discovery/prod/foo:http
 * }}}
 *
 * is the endpoint `http` of serverset `/discovery/prod/foo`
 *
 * Cribbed from https://github.com/twitter/finagle/blob/develop/finagle-serversets/src/main/scala/com/twitter/serverset.scala
 */
class ServersetNamer(zkHost: Path, idPrefix: Path, zk2: BouyantZkResolver) extends Namer {
  def this(zkHost: String, idPrefix: Path) = this(Path.Utf8(zkHost), idPrefix, new BouyantZkResolverImpl)

  /** Bind a name. */
  protected[this] def bind(path: Path, residual: Path = Path.empty): Activity[NameTree[Name]] =
    Try(zk2.resolve(zkHost ++ path)) match {
      case Return(addr) =>
        val act = Activity(addr.map(Activity.Ok(_)))
        act.flatMap {
          case Addr.Neg if !path.isEmpty =>
            val n = path.size
            bind(path.take(n - 1), path.drop(n - 1) ++ residual)
          case Addr.Neg =>
            Activity.value(NameTree.Neg)
          case Addr.Bound(_, _) =>
            // Clients may depend on Name.Bound ids being Paths which resolve
            // back to the same Name.Bound
            val id = idPrefix ++ path
            Activity.value(NameTree.Leaf(Name.Bound(addr, id, residual)))
          case Addr.Pending =>
            Activity.pending
          case Addr.Failed(exc) =>
            Activity.exception(exc)
        }
      case Throw(exc) =>
        Activity.exception(exc)
    }

  // We have to involve a serverset roundtrip here to return a tree. We run the
  // risk of invalidating an otherwise valid tree when there is a bad serverset
  // on an Alt branch that would never be taken. A potential solution to this
  // conundrum is to introduce some form of lazy evaluation of name trees.
  def lookup(path: Path): Activity[NameTree[Name]] = bind(path)
}
